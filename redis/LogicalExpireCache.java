package redis;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 逻辑过期缓存示例：适合热点订单/号源查询，避免同一时刻过期击穿 DB。
 */
public class LogicalExpireCache {

    private final RedissonClient redisson;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService refreshPool;

    public LogicalExpireCache(RedissonClient redisson) {
        this.redisson = redisson;
        this.refreshPool = new ThreadPoolExecutor(
                4, 16,
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2000), // 有界队列，避免雪崩时无限堆积
                new ThreadPoolExecutor.DiscardPolicy() // 队列满了直接丢刷新任务（旧值继续服务）
        );
    }

    /** 缓存载体：数据 + 逻辑过期时间 */
    public static class CacheValue<T> {
        public T data;
        public long logicalExpireAtEpochMs; // 逻辑过期时间（毫秒）

        public CacheValue() {
        }

        public CacheValue(T data, long logicalExpireAtEpochMs) {
            this.data = data;
            this.logicalExpireAtEpochMs = logicalExpireAtEpochMs;
        }
    }

    /**
     * 读取逻辑过期缓存：
     * 1) 命中且未逻辑过期：直接返回
     * 2) 命中但逻辑过期：返回旧值 + 异步刷新（互斥）
     * 3) miss：互斥同步回源一次，写入缓存
     *
     * @param key                Redis key
     * @param typeRef            Jackson 类型（支持泛型）
     * @param loadFromDb         回源函数（例如 DB 查询）
     * @param logicalTtlSeconds  逻辑过期时长（例如 5s/30s/60s）
     * @param physicalTtlSeconds 物理过期时长（要比逻辑 TTL 长，比如 10 倍，防止缓存永久不清）
     */
    public <T> Optional<T> getWithLogicalExpire(
            String key,
            TypeReference<CacheValue<T>> typeRef,
            Function<String, Optional<T>> loadFromDb,
            long logicalTtlSeconds,
            long physicalTtlSeconds) {
        RBucket<String> bucket = redisson.getBucket(key);
        String raw = bucket.get();
        long now = Instant.now().toEpochMilli();

        // 1) 缓存命中
        if (raw != null) {
            CacheValue<T> cv = read(raw, typeRef);
            if (cv != null && cv.data != null) {
                // 1.1 未逻辑过期：直接返回
                if (now < cv.logicalExpireAtEpochMs) {
                    return Optional.of(cv.data);
                }

                // 1.2 逻辑过期：返回旧值，同时异步刷新（只触发一次）
                triggerAsyncRefreshOnce(key, loadFromDb, logicalTtlSeconds, physicalTtlSeconds);
                return Optional.of(cv.data);
            }
        }

        // 2) 真 miss：互斥同步回源（避免击穿）
        String lockKey = "lock:cache:" + key;
        RLock lock = redisson.getLock(lockKey);
        boolean locked = false;
        try {
            // 等待最多 50ms，避免线程堆积；leaseTime 不传让 watchdog 自动续期也行，这里显式给短 lease
            locked = lock.tryLock(50, 5, TimeUnit.SECONDS);
            if (!locked) {
                // 拿不到锁：短暂回读一次（很可能别的线程已经回源写入）
                String raw2 = bucket.get();
                if (raw2 != null) {
                    CacheValue<T> cv2 = read(raw2, typeRef);
                    if (cv2 != null && cv2.data != null) {
                        return Optional.of(cv2.data);
                    }
                }
                // 仍 miss：降级（不打 DB），由上层决定返回“处理中/繁忙”
                return Optional.empty();
            }

            // 二次确认：避免重复回源
            String raw3 = bucket.get();
            if (raw3 != null) {
                CacheValue<T> cv3 = read(raw3, typeRef);
                if (cv3 != null && cv3.data != null) {
                    return Optional.of(cv3.data);
                }
            }

            // 同步回源
            Optional<T> loaded = loadFromDb.apply(key);
            if (loaded.isEmpty()) {
                return Optional.empty();
            }

            // 写缓存：逻辑过期 + 物理过期（物理 TTL 要更长）
            writeCache(bucket, loaded.get(), logicalTtlSeconds, physicalTtlSeconds);
            return loaded;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private <T> void triggerAsyncRefreshOnce(
            String key,
            Function<String, Optional<T>> loadFromDb,
            long logicalTtlSeconds,
            long physicalTtlSeconds) {
        String refreshLockKey = "lock:refresh:" + key;
        RLock refreshLock = redisson.getLock(refreshLockKey);

        // 不等待：抢到就刷新，抢不到说明已经有人在刷新
        boolean locked = refreshLock.tryLock();
        if (!locked)
            return;

        refreshPool.execute(() -> {
            try {
                Optional<T> loaded = loadFromDb.apply(key);
                loaded.ifPresent(val -> {
                    RBucket<String> bucket = redisson.getBucket(key);
                    writeCache(bucket, val, logicalTtlSeconds, physicalTtlSeconds);
                });
            } finally {
                // 必须释放刷新锁
                refreshLock.unlock();
            }
        });
    }

    private <T> void writeCache(RBucket<String> bucket, T data, long logicalTtlSeconds, long physicalTtlSeconds) {
        long now = Instant.now().toEpochMilli();
        long logicalExpireAt = now + TimeUnit.SECONDS.toMillis(logicalTtlSeconds);

        CacheValue<T> cv = new CacheValue<>(data, logicalExpireAt);
        String json = write(cv);

        // 物理 TTL 要比逻辑 TTL 更长，否则逻辑过期还没触发刷新就 key 没了
        bucket.set(json, physicalTtlSeconds, TimeUnit.SECONDS);
    }

    private <T> CacheValue<T> read(String raw, TypeReference<CacheValue<T>> typeRef) {
        try {
            return mapper.readValue(raw, typeRef);
        } catch (Exception e) {
            return null;
        }
    }

    private String write(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
