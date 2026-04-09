package com.lx.governance.common.id;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Snowflake 思路的 64 位<b>无锁</b>分布式 ID 生成器。
 *
 * <h3>ID 结构（共 63 个有效位，最高位恒为 0 保证正数）</h3>
 * <pre>
 *  ┌──────────────────────────────────────────────────────┐
 *  │ 0 │       41 bits timestamp      │ 5 dc │ 5 wk │ 12 seq │
 *  └──────────────────────────────────────────────────────┘
 * </pre>
 * <ul>
 *   <li>时间戳：相对自定义纪元（2024-01-01 UTC）的毫秒差值，可用约 69 年（至 2093 年）。</li>
 *   <li>数据中心 ID（5 位）：支持最多 32 个数据中心。</li>
 *   <li>工作节点 ID（5 位）：每个数据中心内最多 32 个节点，全局共 1024 个节点。</li>
 *   <li>序列号（12 位）：同一毫秒内最多生成 4096 个 ID，理论峰值约 409.6 万 ID/秒/节点。</li>
 * </ul>
 *
 * <h3>相比原版的优化点</h3>
 * <ol>
 *   <li><b>无锁 CAS</b>：用 {@link AtomicLong} 将 {@code (timestamp_delta, sequence)} 压缩到
 *       单个原子变量，以 CAS 循环替代 {@code synchronized}，消除锁争抢，提升高并发吞吐量。</li>
 *   <li><b>更快的时钟</b>：使用 {@code System.currentTimeMillis()} 替代
 *       {@code Instant.now().toEpochMilli()}，减少对象分配与方法调用开销。</li>
 *   <li><b>友好的自旋</b>：自旋等待时调用 {@link Thread#onSpinWait()}，
 *       提示 JVM/CPU 当前处于忙等状态，可降低功耗并提升超线程效率（Java 9+）。</li>
 *   <li><b>分层节点编号</b>：将原 10 位 workerId 拆分为 5 位 datacenterId + 5 位 workerId，
 *       便于多机房部署时的节点管理。</li>
 *   <li><b>可反解调试</b>：提供 {@link #parse(long)} 静态方法，方便日志追踪与问题定位。</li>
 * </ol>
 */
public final class SnowflakeIdGenerator {

    // ─── 自定义纪元：2024-01-01 00:00:00 UTC ─────────────────────────────────
    private static final long EPOCH = 1_704_067_200_000L;

    // ─── 各字段位宽 ──────────────────────────────────────────────────────────
    private static final int SEQUENCE_BITS      = 12;
    private static final int WORKER_ID_BITS     = 5;
    private static final int DATACENTER_ID_BITS = 5;

    // ─── 各字段掩码 ──────────────────────────────────────────────────────────
    static final long MAX_SEQUENCE      = (1L << SEQUENCE_BITS)      - 1;  // 4095
    static final long MAX_WORKER_ID     = (1L << WORKER_ID_BITS)     - 1;  // 31
    static final long MAX_DATACENTER_ID = (1L << DATACENTER_ID_BITS) - 1;  // 31

    // ─── 各字段左移偏移量 ─────────────────────────────────────────────────────
    private static final int WORKER_ID_SHIFT     = SEQUENCE_BITS;                                       // 12
    private static final int DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;                      // 17
    private static final int TIMESTAMP_SHIFT     = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS; // 22

    // ─── 实例字段 ─────────────────────────────────────────────────────────────
    private final long datacenterId;
    private final long workerId;
    private final long maxBackwardMs;

    /**
     * 原子状态：高 (64-SEQUENCE_BITS) 位存储 {@code (currentTimeMillis() - EPOCH)}，
     * 低 {@value #SEQUENCE_BITS} 位存储当前序列号。
     * <p>通过 CAS 保证对该字段的更新是原子且可见的，无需额外同步。
     */
    private final AtomicLong state;

    /**
     * 单数据中心便捷构造器，{@code datacenterId} 默认为 0。
     *
     * @param workerId      工作节点编号，范围 [0, {@value #MAX_WORKER_ID}]
     * @param maxBackwardMs 允许的最大时钟回拨毫秒数；超过则抛出异常以保证唯一性
     */
    public SnowflakeIdGenerator(long workerId, long maxBackwardMs) {
        this(0L, workerId, maxBackwardMs);
    }

    /**
     * 全参数构造器。
     *
     * @param datacenterId  数据中心编号，范围 [0, {@value #MAX_DATACENTER_ID}]
     * @param workerId      工作节点编号，范围 [0, {@value #MAX_WORKER_ID}]
     * @param maxBackwardMs 允许的最大时钟回拨毫秒数；超过则抛出异常以保证唯一性
     */
    public SnowflakeIdGenerator(long datacenterId, long workerId, long maxBackwardMs) {
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                    "datacenterId out of range [0, " + MAX_DATACENTER_ID + "]: " + datacenterId);
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "workerId out of range [0, " + MAX_WORKER_ID + "]: " + workerId);
        }
        this.datacenterId  = datacenterId;
        this.workerId      = workerId;
        this.maxBackwardMs = Math.max(0L, maxBackwardMs);
        // 以当前时间戳差值初始化状态，序列号起始为 0
        long initDelta = System.currentTimeMillis() - EPOCH;
        this.state = new AtomicLong(initDelta << SEQUENCE_BITS);
    }

    /**
     * 生成下一个全局唯一 ID（无锁实现）。
     *
     * <p>核心思路：将 {@code (timestamp_delta, sequence)} 压缩到一个 {@code long} 中，
     * 通过 CAS 循环原子地推进状态。相比 {@code synchronized}，在高并发场景下
     * 避免了线程阻塞与上下文切换，提升整体吞吐量。
     *
     * <ul>
     *   <li>新毫秒：重置序列号为 0，CAS 更新时间戳。</li>
     *   <li>同毫秒：递增序列号；序列耗尽时自旋等待进入下一毫秒。</li>
     *   <li>时钟回拨 ≤ maxBackwardMs：自旋等待时钟追上，再继续发号。</li>
     *   <li>时钟回拨 &gt; maxBackwardMs：立即抛出异常，优先保证唯一性。</li>
     * </ul>
     *
     * @return 64 位全局唯一正整数 ID
     * @throws IllegalStateException 时钟回拨幅度超过 {@code maxBackwardMs} 时
     */
    public long nextId() {
        while (true) {
            final long currentState = state.get();
            final long lastTsDelta  = currentState >> SEQUENCE_BITS;
            final long nowDelta     = System.currentTimeMillis() - EPOCH;

            final long newState;

            if (nowDelta > lastTsDelta) {
                // ── 进入新毫秒：重置序列号为 0 ──────────────────────────────
                newState = nowDelta << SEQUENCE_BITS;

            } else if (nowDelta == lastTsDelta) {
                // ── 同一毫秒内：递增序列号 ───────────────────────────────────
                long nextSeq = (currentState + 1L) & MAX_SEQUENCE;
                if (nextSeq == 0L) {
                    // 序列号在当前毫秒内已耗尽，等待进入下一毫秒
                    long nextDelta = awaitNextMillis(lastTsDelta);
                    newState = nextDelta << SEQUENCE_BITS;
                } else {
                    newState = (lastTsDelta << SEQUENCE_BITS) | nextSeq;
                }

            } else {
                // ── 时钟回拨 ────────────────────────────────────────────────
                long backwardMs = lastTsDelta - nowDelta;
                if (backwardMs <= maxBackwardMs) {
                    // 小幅回拨：等待系统时钟追上上次发号时间点
                    long recovered = awaitNextMillis(lastTsDelta - 1L);
                    newState = recovered << SEQUENCE_BITS;
                } else {
                    throw new IllegalStateException(String.format(
                            "Clock moved backwards %d ms (max allowed: %d ms). " +
                            "Refusing to generate id to guarantee uniqueness.",
                            backwardMs, maxBackwardMs));
                }
            }

            // CAS 原子更新；若失败说明其他线程已抢先更新，重新读取状态后再试
            if (state.compareAndSet(currentState, newState)) {
                long tsDelta = newState >> SEQUENCE_BITS;
                long seq     = newState & MAX_SEQUENCE;
                return (tsDelta       << TIMESTAMP_SHIFT)
                     | (datacenterId  << DATACENTER_ID_SHIFT)
                     | (workerId      << WORKER_ID_SHIFT)
                     | seq;
            }

            // 提示 JVM/CPU 当前正在自旋等待，有助于降低功耗和超线程竞争
            Thread.onSpinWait();
        }
    }

    /**
     * 生成下一个 ID 并以字符串形式返回。
     *
     * <p>在 JSON 序列化场景中，JavaScript 的 {@code Number} 类型仅能精确表示 53 位整数，
     * 建议将 ID 以字符串传输，避免前端精度丢失。
     *
     * @return ID 的十进制字符串表示
     */
    public String nextIdString() {
        return Long.toString(nextId());
    }

    /**
     * 将 ID 反解为各组成字段，方便日志追踪与问题定位。
     *
     * @param id 由本生成器生成的 ID
     * @return 可读字符串，例如 {@code [timestamp=1712345678901, dc=1, worker=3, seq=42]}
     */
    public static String parse(long id) {
        long seq    =  id                    & MAX_SEQUENCE;
        long worker = (id >> WORKER_ID_SHIFT)     & MAX_WORKER_ID;
        long dc     = (id >> DATACENTER_ID_SHIFT) & MAX_DATACENTER_ID;
        long ts     = (id >> TIMESTAMP_SHIFT) + EPOCH;
        return "[timestamp=" + ts + ", dc=" + dc + ", worker=" + worker + ", seq=" + seq + "]";
    }

    // ─── 内部工具 ──────────────────────────────────────────────────────────────

    /**
     * 自旋等待，直到 {@code (System.currentTimeMillis() - EPOCH) > targetTsDelta}。
     *
     * <p>循环内调用 {@link Thread#onSpinWait()} 以向运行时提示忙等状态。
     *
     * @param targetTsDelta 目标时间戳差值（相对于 EPOCH 的毫秒数），等待时钟超过该值
     * @return 超过 targetTsDelta 后的最新 {@code (currentTimeMillis - EPOCH)}
     */
    private static long awaitNextMillis(long targetTsDelta) {
        long nowDelta;
        do {
            Thread.onSpinWait();
            nowDelta = System.currentTimeMillis() - EPOCH;
        } while (nowDelta <= targetTsDelta);
        return nowDelta;
    }
}
