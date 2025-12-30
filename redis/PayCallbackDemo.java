package redis;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 支付回调端到端示例（含：限流、验签、幂等、Redisson锁、MySQL状态机+Outbox、RabbitMQ投递）
 *
 * 重点：
 * 1) 入口快速止血：限流 + 幂等(PROCESSING/DONE)
 * 2) 正确性归 MySQL：状态机条件更新 + outbox 唯一约束
 * 3) MQ 只做最终一致：靠 outbox 投递器保证可达
 * 4) 缓存-DB 双写一致性：
 * - DB✅ Cache❌：提交后写缓存失败 -> 记录修复任务 -> 后台修复器补写
 * - Cache✅ DB❌：错误提前写缓存 -> 回滚后清缓存 + tombstone 防误读
 * - 缓存写入使用“版本单调写”防乱序脏写
 */
public class PayCallbackDemo {

    // ========= 模拟依赖（上线换成真实组件） =========

    // 模拟 Redis：计数器、KV（含TTL）+ 单调写 + 修复任务扫描
    private final MockRedis redis = new MockRedis();

    // 模拟 Redisson：分布式锁（这里只是本地锁）
    private final MockRedisson redisson = new MockRedisson();

    // 模拟 MySQL：支付单表 + outbox 表（带事务 hook）
    private final MockMySQL mysql = new MockMySQL();

    // 模拟 RabbitMQ：发布消息（可模拟失败）
    private final MockRabbitMQ rabbit = new MockRabbitMQ();

    // outbox 投递器（后台线程）
    private final OutboxDispatcher dispatcher = new OutboxDispatcher(mysql, rabbit);

    // 缓存修复器（后台线程）：修复 DB✅ Cache❌
    private final CacheRepairWorker cacheRepair = new CacheRepairWorker(redis, mysql);

    // 验签器（演示用 HMAC）
    private final SignatureVerifier verifier = new SignatureVerifier("demo-secret");

    // 可选：布隆过滤器（这里用 set 模拟；真实用 Redisson RBloomFilter）
    private final MockBloomFilter bloom = new MockBloomFilter();

    // ========= Controller（回调入口） =========

    /**
     * 模拟一个支付回调接口方法（你可以把它放到 @RestController 里）
     *
     * 入参：rawBody、headers、clientIp
     * 返回：渠道需要的 success/failed 文本（不同渠道不同，这里统一 "success"/"failed"）
     */
    public String payCallbackEndpoint(String rawBody, Map<String, String> headers, String clientIp) {

        // 0) 入口防御：基础字段校验（省略细枝末节）
        if (rawBody == null || rawBody.isEmpty())
            return "failed";
        if (rawBody.length() > 1024 * 1024)
            return "failed"; // 防止超大 body 打你

        // 1) 基础限流（Redis 计数器）
        // 生产建议：按 channel + ip / merchant 做多维限流，这里先演示最常见 ip+秒
        String channel = headers.getOrDefault("X-Channel", "UNKNOWN");
        String rateKey = "pay:rate:cb:v1:" + channel + ":" + clientIp + ":" + Instant.now().getEpochSecond();
        long qps = redis.incr(rateKey, 2); // TTL 2 秒（简化实现）
        if (qps > 50) { // 示例阈值：每秒 50 次
            // 限流触发：不要做任何重活（DB/锁/MQ），直接快速失败或按渠道要求返回 busy
            return "failed";
        }

        // 2) 验签（必须用 raw body；验签不过一律拒绝）
        String sign = headers.getOrDefault("X-Sign", "");
        if (!verifier.verifyHmacSha256(rawBody, sign)) {
            // 验签失败：拒绝 + 记安全日志/风控（这里略）
            return "failed";
        }

        // 3) 解析最小字段（只解析业务必要字段）
        // 真实：用 Jackson 解析 JSON；这里为了演示，用一个简单 parse（假设 kv 格式）
        PayCallback cb = PayCallback.parse(rawBody);
        if (cb == null || cb.orderId == null || cb.notifyId == null)
            return "failed";

        // 4)（可选）布隆过滤器：用于减压分流，不作为硬拦截
        boolean mightExist = bloom.contains(cb.orderId);
        if (!mightExist) {
            // 早到/疑似异常：收容（只存摘要，不存大原文）
            String inboxKey = "pay:inbox:cb:v1:" + cb.orderId;
            redis.setEx(inboxKey, 7 * 24 * 3600, "digest=" + cb.digest());
            // 直接 success 止血（防渠道重试风暴）
            return "success";
        }

        // 5) 幂等键（两阶段：PROCESSING -> DONE）
        String idemKey = "pay:idem:cb:v1:" + channel + ":" + cb.notifyId;
        String existing = redis.get(idemKey);

        // 命中 DONE：直接秒回 success（彻底止血）
        if ("DONE".equals(existing)) {
            return "success";
        }

        // 未命中或不是 DONE：尝试抢占 PROCESSING（NX）
        long processingTtlSec = ttlWithJitterSec(10 * 60, 60); // 10m + jitter(0~60s)
        boolean first = redis.setNxEx(idemKey, processingTtlSec, "PROCESSING");
        if (!first) {
            // 已有并发在处理或刚处理完（PROCESSING 或 DONE）
            return "success"; // 让渠道停重试
        }

        // 6) 订单锁（Redisson）
        String lockKey = "pay:lock:order:v1:{" + cb.orderId + "}";
        MockRedisson.Lock lock = redisson.getLock(lockKey);

        boolean locked = lock.tryLock(100, TimeUnit.MILLISECONDS);
        if (!locked) {
            // 拿不到锁：有人在处理这个订单
            return "success"; // 止血
        }

        try {
            // -----------------------------
            // 双写一致性：准备事务内计算出的“缓存回填信息”，commit 后写缓存
            // -----------------------------
            final Holder<String> cacheKeyHolder = new Holder<>();
            final Holder<String> cacheValHolder = new Holder<>();
            final Holder<Long> cacheVerHolder = new Holder<>();

            // （可选）演示错误：先写缓存后写库。生产千万别这样写。
            boolean badWriteCacheFirst = false; // 改成 true 可演示 Cache✅ DB❌ 的兜底
            if (badWriteCacheFirst) {
                redis.setEx(payCacheKey(cb.orderId), 3600, encodePayCache("PAID", 9999));
            }

            // 7) DB 最小事务：条件更新 + outbox 插入（核心正确性）
            MockMySQL.TxResult tx = mysql.inTransaction(() -> {

                PayOrder order = mysql.payOrderTable.get(cb.orderId);
                if (order == null) {
                    // 回调先于建单：收容箱
                    String inboxKey = "pay:inbox:cb:v1:" + cb.orderId;
                    redis.setEx(inboxKey, 7 * 24 * 3600, "digest=" + cb.digest());

                    // 不要直接 DONE（否则建单后处理不了），改 PENDING 较长 TTL
                    redis.setEx(idemKey, ttlWithJitterSec(24 * 3600, 3600), "PENDING"); // 1d +/- 1h
                    return MockMySQL.TxResult.ok("EARLY_CALLBACK_INBOX");
                }

                // 7.1 业务校验：金额
                if (order.amount.compareTo(cb.amount) != 0) {
                    return MockMySQL.TxResult.fail("AMOUNT_MISMATCH");
                }

                // 7.2 状态机条件更新：PAYING/INIT -> PAID（单调推进）
                long newVer = mysql.payOrderTable.conditionalUpdatePaid(cb.orderId, cb.channelTxnId, cb.paidAtEpochMs);
                if (newVer < 0) {
                    // 已是终态或不允许推进：视为幂等命中/乱序
                    // 可选：仍准备一次“回填缓存”（单调写会防止倒退）
                    PayOrder cur = mysql.payOrderTable.get(cb.orderId);
                    if (cur != null) {
                        cacheKeyHolder.value = payCacheKey(cb.orderId);
                        cacheValHolder.value = encodePayCache(cur.status, cur.version);
                        cacheVerHolder.value = cur.version;
                    }
                    return MockMySQL.TxResult.ok("ALREADY_TERMINAL_OR_OUT_OF_ORDER");
                }

                // 7.3 outbox 插入（唯一事件 id 防重复）
                String eventId = "pay:" + cb.orderId + ":PAID:" + newVer;
                OutboxEvent evt = new OutboxEvent(eventId, cb.orderId, "PAY_PAID", newVer, cb.toJson());
                mysql.outboxTable.insertIfAbsent(evt); // UNIQUE(eventId)

                // 7.4 准备 afterCommit 回填缓存（不要在事务内写 Redis）
                cacheKeyHolder.value = payCacheKey(cb.orderId);
                cacheValHolder.value = encodePayCache("PAID", newVer);
                cacheVerHolder.value = newVer;

                // 7.5 模拟偶发 DB 失败（演示用，可删）
                // if (ThreadLocalRandom.current().nextInt(100) < 1) return
                // MockMySQL.TxResult.fail("DB_TIMEOUT");

                return MockMySQL.TxResult.ok("PAID_UPDATED");
            },

                    // afterCommit：DB 成功后写缓存（可能失败 -> 记录修复任务）
                    () -> {
                        if (cacheKeyHolder.value == null)
                            return;

                        long ttlSec;
                        // 终态长TTL；非终态短TTL（这里我们写的是 PAID，给长 TTL）
                        ttlSec = ttlWithJitterSec(6 * 3600, 3600); // 6h +/-1h

                        boolean ok;
                        try {
                            ok = redis.setIfNewerVersion(
                                    cacheKeyHolder.value,
                                    ttlSec,
                                    cacheValHolder.value,
                                    cacheVerHolder.value);
                            // 清理 tombstone（如果存在）
                            redis.del(payCacheTombstoneKey(cb.orderId));
                        } catch (Exception e) {
                            ok = false;
                        }

                        if (!ok) {
                            // DB✅ Cache❌：记录修复任务（demo 用 redis key；生产建议写 repair 表或 MQ）
                            String fixKey = payCacheFixKey(cb.orderId, cacheVerHolder.value);
                            redis.setEx(fixKey, ttlWithJitterSec(24 * 3600, 3600), "need_fix");
                        }
                    },

                    // afterRollback：事务失败，兜底处理“缓存可能被提前写导致的假象”
                    () -> {
                        // tombstone：短 TTL 防误读（提示读路径走 DB）
                        String tomb = payCacheTombstoneKey(cb.orderId);
                        redis.setEx(tomb, ttlWithJitterSec(120, 30), "DIRTY");

                        // 删除订单缓存，迫使后续读走 DB（或读修复）
                        redis.del(payCacheKey(cb.orderId));
                    });

            // 7.6 DB 失败：决定回给渠道 success 还是 failed
            if (!tx.success) {
                // DB 没可靠落地 => 返回 failed 让渠道重试
                // 但你已经写了 PROCESSING NX：为了不形成并发风暴，把 idemKey 设为 FAILED(短TTL)
                redis.setEx(idemKey, ttlWithJitterSec(120, 30), "FAILED"); // 2min +/- 30s
                return "failed";
            }

            // 8) 设置 DONE（长期幂等记忆）
            long doneTtlSec = ttlWithJitterSec(7L * 24 * 3600, 6 * 3600); // 7d +/- 6h
            redis.setEx(idemKey, doneTtlSec, "DONE");

            // 9) 快速返回 success（让渠道停止重试）
            // outbox 异步投递：不阻塞回调 RT
            dispatcher.kickAsyncOnce();

            return "success";
        } finally {
            lock.unlock();
        }
    }

    // ========= 后台：Outbox 投递器（模拟） =========

    /**
     * outbox 投递器：把 DB 里 NEW/RETRY 事件投递到 RabbitMQ
     * - 失败会累计 retryCount
     * - 达到阈值进入 DEAD（模拟 DLQ）
     */
    static class OutboxDispatcher {
        private final MockMySQL mysql;
        private final MockRabbitMQ rabbit;

        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final ExecutorService onceExecutor = Executors.newSingleThreadExecutor();
        private final AtomicLong kicked = new AtomicLong(0);

        OutboxDispatcher(MockMySQL mysql, MockRabbitMQ rabbit) {
            this.mysql = mysql;
            this.rabbit = rabbit;
            // 生产：固定周期扫描投递（比如 200ms/1s）
            scheduler.scheduleWithFixedDelay(this::dispatchBatchSafe, 200, 200, TimeUnit.MILLISECONDS);
        }

        void kickAsyncOnce() {
            // 防止每次回调都触发一堆投递线程；只“点火”即可
            long n = kicked.incrementAndGet();
            if (n == 1) {
                onceExecutor.execute(() -> {
                    try {
                        dispatchBatchSafe();
                    } finally {
                        kicked.set(0);
                    }
                });
            }
        }

        private void dispatchBatchSafe() {
            try {
                dispatchBatch();
            } catch (Exception e) {
                // 真实：打点报警
            }
        }

        private void dispatchBatch() {
            List<OutboxEvent> batch = mysql.outboxTable.fetchForDispatch(50);
            for (OutboxEvent e : batch) {
                boolean ok = rabbit.publish(
                        "ex.pay.events",
                        "rk.pay." + e.aggregateId, // routingKey 可用 orderId，RabbitMQ 实际建议 direct/topic
                        e.eventId,
                        e.payload,
                        Map.of("orderId", e.aggregateId, "version", String.valueOf(e.version)));

                if (ok) {
                    mysql.outboxTable.markSent(e.eventId);
                } else {
                    mysql.outboxTable.markRetryOrDead(e.eventId, 10); // 最大重试 10
                }
            }
        }
    }

    // ========= 后台：缓存修复器（修复 DB✅ Cache❌） =========
    static class CacheRepairWorker {
        private final MockRedis redis;
        private final MockMySQL mysql;
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        CacheRepairWorker(MockRedis redis, MockMySQL mysql) {
            this.redis = redis;
            this.mysql = mysql;
            scheduler.scheduleWithFixedDelay(this::runSafe, 500, 500, TimeUnit.MILLISECONDS);
        }

        void runSafe() {
            try {
                runOnce();
            } catch (Exception ignored) {
            }
        }

        void runOnce() {
            // demo：扫描 fix key（生产不要扫 key，应该用队列/表）
            List<String> fixKeys = redis.keysByPrefix("pay:fix:cache:v1:");
            for (String fixKey : fixKeys) {
                // fixKey 形如 pay:fix:cache:v1:{orderId}:<ver>
                String[] parts = fixKey.split(":");
                if (parts.length < 6)
                    continue;
                String orderPart = parts[4]; // "{orderId}"
                String orderId = orderPart.replace("{", "").replace("}", "");

                PayOrder o = mysql.payOrderTable.get(orderId);
                if (o == null) {
                    redis.del(fixKey);
                    continue;
                }

                String cacheKey = payCacheKey(orderId);
                String val = encodePayCache(o.status, o.version);

                long ttlSec = ("PAID".equals(o.status) || "CLOSED".equals(o.status) || "FAILED".equals(o.status))
                        ? ttlWithJitterSec(6 * 3600, 3600)
                        : ttlWithJitterSec(60, 20);

                boolean ok = redis.setIfNewerVersion(cacheKey, ttlSec, val, o.version);
                // 无论 ok / 被拒绝（说明已有更高版本），都可以移除 fixKey
                redis.del(fixKey);
            }
        }
    }

    // ========= 工具：TTL jitter =========
    static long ttlWithJitterSec(long baseSec, long jitterSec) {
        long r = ThreadLocalRandom.current().nextLong(jitterSec + 1);
        return baseSec + r;
    }

    // ========= 回调数据结构（示例） =========
    static class PayCallback {
        String orderId;
        String notifyId;
        BigDecimal amount;
        String channelTxnId;
        long paidAtEpochMs;

        static PayCallback parse(String raw) {
            // 演示：raw 假设形如 "orderId=O1&notifyId=N1&amount=12.34&txn=T1&paidAt=1700000000000"
            Map<String, String> kv = new HashMap<>();
            for (String part : raw.split("&")) {
                String[] arr = part.split("=", 2);
                if (arr.length == 2)
                    kv.put(arr[0], arr[1]);
            }
            PayCallback cb = new PayCallback();
            cb.orderId = kv.get("orderId");
            cb.notifyId = kv.get("notifyId");
            cb.channelTxnId = kv.getOrDefault("txn", "");
            cb.paidAtEpochMs = Long.parseLong(kv.getOrDefault("paidAt", String.valueOf(System.currentTimeMillis())));
            cb.amount = new BigDecimal(kv.getOrDefault("amount", "0"));
            return cb;
        }

        String digest() {
            return Integer.toHexString(Objects.hash(orderId, notifyId, amount, channelTxnId, paidAtEpochMs));
        }

        String toJson() {
            return "{"
                    + "\"orderId\":\"" + orderId + "\","
                    + "\"notifyId\":\"" + notifyId + "\","
                    + "\"amount\":\"" + amount + "\","
                    + "\"channelTxnId\":\"" + channelTxnId + "\","
                    + "\"paidAt\":" + paidAtEpochMs
                    + "}";
        }
    }

    // ========= MySQL 模拟（支付单 + outbox，带事务 hook） =========
    static class PayOrder {
        String orderId;
        BigDecimal amount;
        String status; // INIT, PAYING, PAID, CLOSED...
        long version; // 单调递增
        String channelTxnId;
        long paidAt;

        PayOrder(String orderId, BigDecimal amount) {
            this.orderId = orderId;
            this.amount = amount;
            this.status = "PAYING";
            this.version = 1;
        }
    }

    static class OutboxEvent {
        final String eventId;
        final String aggregateId; // orderId
        final String eventType;
        final long version;
        final String payload;

        volatile String status = "NEW"; // NEW, SENT, RETRY, DEAD
        volatile int retryCount = 0;

        OutboxEvent(String eventId, String aggregateId, String eventType, long version, String payload) {
            this.eventId = eventId;
            this.aggregateId = aggregateId;
            this.eventType = eventType;
            this.version = version;
            this.payload = payload;
        }
    }

    static class MockMySQL {
        final PayOrderTable payOrderTable = new PayOrderTable();
        final OutboxTable outboxTable = new OutboxTable();

        static class TxResult {
            final boolean success;
            final String msg;

            private TxResult(boolean success, String msg) {
                this.success = success;
                this.msg = msg;
            }

            static TxResult ok(String msg) {
                return new TxResult(true, msg);
            }

            static TxResult fail(String msg) {
                return new TxResult(false, msg);
            }
        }

        // 模拟事务：支持 afterCommit/afterRollback hook（真实环境用 Spring 事务同步器）
        TxResult inTransaction(Supplier<TxResult> fn, Runnable afterCommit, Runnable afterRollback) {
            TxResult r;
            try {
                r = fn.get();
                if (r.success) {
                    if (afterCommit != null)
                        afterCommit.run();
                } else {
                    if (afterRollback != null)
                        afterRollback.run();
                }
                return r;
            } catch (Exception e) {
                if (afterRollback != null)
                    afterRollback.run();
                return TxResult.fail("TX_EXCEPTION:" + e.getMessage());
            }
        }

        static class PayOrderTable {
            private final ConcurrentHashMap<String, PayOrder> map = new ConcurrentHashMap<>();

            PayOrder get(String orderId) {
                return map.get(orderId);
            }

            void insert(PayOrder o) {
                map.put(o.orderId, o);
            }

            /**
             * 条件更新：PAYING/INIT -> PAID
             * 返回新 version；若不允许推进返回 -1
             */
            long conditionalUpdatePaid(String orderId, String channelTxnId, long paidAtEpochMs) {
                PayOrder o = map.get(orderId);
                if (o == null)
                    return -1;

                synchronized (o) {
                    if ("PAID".equals(o.status) || "CLOSED".equals(o.status) || "FAILED".equals(o.status)) {
                        return -1; // 终态不回退
                    }
                    if ("PAYING".equals(o.status) || "INIT".equals(o.status)) {
                        o.status = "PAID";
                        o.channelTxnId = channelTxnId;
                        o.paidAt = paidAtEpochMs;
                        o.version++;
                        return o.version;
                    }
                    return -1;
                }
            }
        }

        static class OutboxTable {
            private final ConcurrentHashMap<String, OutboxEvent> map = new ConcurrentHashMap<>();

            void insertIfAbsent(OutboxEvent evt) {
                map.putIfAbsent(evt.eventId, evt);
            }

            List<OutboxEvent> fetchForDispatch(int limit) {
                List<OutboxEvent> res = new ArrayList<>();
                for (OutboxEvent e : map.values()) {
                    if (res.size() >= limit)
                        break;
                    if ("NEW".equals(e.status) || "RETRY".equals(e.status)) {
                        res.add(e);
                    }
                }
                return res;
            }

            void markSent(String eventId) {
                OutboxEvent e = map.get(eventId);
                if (e != null)
                    e.status = "SENT";
            }

            void markRetryOrDead(String eventId, int maxRetry) {
                OutboxEvent e = map.get(eventId);
                if (e == null)
                    return;
                e.retryCount++;
                if (e.retryCount >= maxRetry) {
                    e.status = "DEAD";
                } else {
                    e.status = "RETRY";
                }
            }
        }
    }

    // ========= Redis 模拟（支持单调写 + 修复任务扫描） =========
    static class MockRedis {
        private static class Val {
            final String v;
            final long expireAtMs;

            Val(String v, long expireAtMs) {
                this.v = v;
                this.expireAtMs = expireAtMs;
            }
        }

        private final ConcurrentHashMap<String, Val> kv = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

        String get(String key) {
            Val val = kv.get(key);
            if (val == null)
                return null;
            if (val.expireAtMs > 0 && System.currentTimeMillis() > val.expireAtMs) {
                kv.remove(key);
                return null;
            }
            return val.v;
        }

        void del(String key) {
            kv.remove(key);
        }

        void setEx(String key, long ttlSec, String value) {
            long exp = ttlSec <= 0 ? 0 : (System.currentTimeMillis() + ttlSec * 1000);
            kv.put(key, new Val(value, exp));
        }

        boolean setNxEx(String key, long ttlSec, String value) {
            long exp = ttlSec <= 0 ? 0 : (System.currentTimeMillis() + ttlSec * 1000);
            return kv.putIfAbsent(key, new Val(value, exp)) == null;
        }

        long incr(String key, long ttlSec) {
            AtomicLong c = counters.computeIfAbsent(key, k -> new AtomicLong(0));
            return c.incrementAndGet();
        }

        /**
         * 单调写（类 Lua CAS）：只有 newVersion > oldVersion 才覆盖。
         */
        boolean setIfNewerVersion(String key, long ttlSec, String value, long newVersion) {
            for (;;) {
                Val old = kv.get(key);
                long oldVer = old == null ? -1 : decodeVersion(old.v);
                if (old != null && old.expireAtMs > 0 && System.currentTimeMillis() > old.expireAtMs) {
                    kv.remove(key);
                    old = null;
                    oldVer = -1;
                }
                if (newVersion <= oldVer)
                    return false;

                long exp = ttlSec <= 0 ? 0 : (System.currentTimeMillis() + ttlSec * 1000);
                Val nv = new Val(value, exp);

                if (old == null) {
                    if (kv.putIfAbsent(key, nv) == null)
                        return true;
                } else {
                    if (kv.replace(key, old, nv))
                        return true;
                }
            }
        }

        List<String> keysByPrefix(String prefix) {
            List<String> res = new ArrayList<>();
            for (String k : kv.keySet()) {
                if (k.startsWith(prefix)) {
                    if (get(k) != null)
                        res.add(k);
                }
            }
            return res;
        }
    }

    // ========= Redisson 锁模拟 =========
    static class MockRedisson {
        private final ConcurrentHashMap<String, Semaphore> locks = new ConcurrentHashMap<>();

        Lock getLock(String key) {
            locks.putIfAbsent(key, new Semaphore(1));
            return new Lock(locks.get(key));
        }

        static class Lock {
            private final Semaphore sem;
            private boolean held = false;

            Lock(Semaphore sem) {
                this.sem = sem;
            }

            boolean tryLock(long time, TimeUnit unit) {
                try {
                    held = sem.tryAcquire(time, unit);
                    return held;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            void unlock() {
                if (held) {
                    held = false;
                    sem.release();
                }
            }
        }
    }

    // ========= RabbitMQ 模拟 =========
    static class MockRabbitMQ {
        private final Random random = new Random();
        private final List<String> delivered = new CopyOnWriteArrayList<>();

        boolean publish(String exchange, String routingKey, String messageId, String body,
                Map<String, String> headers) {
            // 模拟 10% 失败（网络抖动/confirm 超时）
            boolean ok = random.nextInt(10) != 0;
            if (ok) {
                delivered.add(messageId + " -> " + exchange + ":" + routingKey);
            }
            return ok;
        }
    }

    // ========= 布隆模拟 =========
    static class MockBloomFilter {
        private final Set<String> set = ConcurrentHashMap.newKeySet();

        boolean contains(String x) {
            return set.contains(x);
        }

        void add(String x) {
            set.add(x);
        }
    }

    // ========= 验签器（示例 HMAC SHA-256） =========
    static class SignatureVerifier {
        private final String secret;

        SignatureVerifier(String secret) {
            this.secret = secret;
        }

        boolean verifyHmacSha256(String body, String providedHex) {
            // 演示用：真实你会用标准 HMAC 算法，这里只做“伪验签”
            String expected = Integer.toHexString(Objects.hash(body + secret));
            return expected.equalsIgnoreCase(providedHex);
        }
    }

    // ========= Holder =========
    static class Holder<T> {
        T value;
    }

    // ========= 缓存 key / 修复 key / tombstone =========
    private static String payCacheKey(String orderId) {
        return "pay:cache:order:v1:{" + orderId + "}:status";
    }

    private static String payCacheFixKey(String orderId, long version) {
        return "pay:fix:cache:v1:{" + orderId + "}:" + version;
    }

    private static String payCacheTombstoneKey(String orderId) {
        return "pay:tomb:cache:v1:{" + orderId + "}";
    }

    // ========= 缓存值编码/解码 =========
    private static String encodePayCache(String status, long version) {
        return "st=" + status + ";ver=" + version + ";ts=" + System.currentTimeMillis();
    }

    private static long decodeVersion(String cacheVal) {
        if (cacheVal == null)
            return -1;
        int idx = cacheVal.indexOf("ver=");
        if (idx < 0)
            return -1;
        int end = cacheVal.indexOf(';', idx);
        String num = (end < 0) ? cacheVal.substring(idx + 4) : cacheVal.substring(idx + 4, end);
        try {
            return Long.parseLong(num);
        } catch (Exception e) {
            return -1;
        }
    }

    // ========= 演示 main =========
    public static void main(String[] args) throws Exception {
        PayCallbackDemo demo = new PayCallbackDemo();

        // 先模拟建单（写 DB + 写布隆）
        PayOrder o = new PayOrder("O1001", new BigDecimal("12.34"));
        demo.mysql.payOrderTable.insert(o);
        demo.bloom.add("O1001");

        // 构造回调
        String body = "orderId=O1001&notifyId=N9001&amount=12.34&txn=T123&paidAt=" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Channel", "WX");
        headers.put("X-Sign", Integer.toHexString(Objects.hash(body + "demo-secret")));

        // 调接口
        String resp = demo.payCallbackEndpoint(body, headers, "1.2.3.4");
        System.out.println("callback resp = " + resp);

        // 等 outbox 投递几轮 + 缓存修复轮询
        Thread.sleep(1500);

        // 打印一下缓存状态
        System.out.println("cache = " + demo.redis.get(payCacheKey("O1001")));
        System.out.println("idem  = " + demo.redis.get("pay:idem:cb:v1:WX:N9001"));

        System.out.println("done");
    }
}