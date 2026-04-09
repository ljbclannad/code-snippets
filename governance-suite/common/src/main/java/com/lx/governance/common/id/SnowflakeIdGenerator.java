package com.lx.governance.common.id;

import java.time.Instant;

/**
 * 基于 Snowflake 思路的 64 位分布式 ID 生成器。
 *
 * <p>原理：把一个 long 型 ID 拆成三部分拼接而成：
 * 时间戳差值 + 工作节点编号 + 同毫秒内序列号。这样既能在整体上保持趋势递增，
 * 又能在多节点部署时降低 ID 冲突风险，并且不依赖数据库自增或远程发号服务。
 *
 * <p>这里采用 10 位 workerId 和 12 位 sequence，意味着最多支持 1024 个节点，
 * 每个节点每毫秒最多生成 4096 个 ID。为了避免时钟回拨导致重复 ID，本实现允许在小幅回拨时短暂等待，
 * 超过阈值则直接失败，优先保证唯一性而不是继续带风险地发号。
 */
public final class SnowflakeIdGenerator {
    /** 自定义纪元，缩短时间戳占用位数并延长可用时间范围。 */
    private static final long EPOCH = 1704067200000L;
    /** 工作节点位宽，决定最多可分配多少个发号节点。 */
    private static final int WORKER_ID_BITS = 10;
    /** 毫秒内自增序列位宽，决定单节点每毫秒的发号上限。 */
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;

    private final long workerId;
    private final long maxBackwardMs;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    /**
     * @param workerId 当前节点编号，范围为 0 ~ 1023
     * @param maxBackwardMs 允许的最大时钟回拨毫秒数；超过该阈值直接拒绝发号
     */
    public SnowflakeIdGenerator(long workerId, long maxBackwardMs) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId out of range: 0~" + MAX_WORKER_ID);
        }
        this.workerId = workerId;
        this.maxBackwardMs = Math.max(0, maxBackwardMs);
    }

    /**
     * 生成下一个全局唯一 ID。
     *
     * <p>方法使用 synchronized 保证单实例内的并发安全：同一时刻只允许一个线程更新
     * lastTimestamp 和 sequence，避免同毫秒下产生重复序列号。
     */
    public synchronized long nextId() {
        long now = currentTimeMillis();
        if (now < lastTimestamp) {
            long diff = lastTimestamp - now;
            if (diff <= maxBackwardMs) {
                // 小幅回拨时等待到上次发号时间点，避免生成重复 ID。
                now = waitUntil(lastTimestamp);
            } else {
                throw new IllegalStateException("Clock moved backwards. Refuse to generate id for " + diff + " ms");
            }
        }
        if (now == lastTimestamp) {
            // 同一毫秒内递增序列；溢出后阻塞到下一毫秒继续发号。
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                now = waitUntil(lastTimestamp + 1);
            }
        } else {
            // 进入新毫秒后序列从 0 重新开始。
            sequence = 0;
        }
        lastTimestamp = now;
        long timestampPart = (now - EPOCH) << TIMESTAMP_SHIFT;
        long workerPart = workerId << WORKER_ID_SHIFT;
        return timestampPart | workerPart | sequence;
    }

    private long currentTimeMillis() {
        return Instant.now().toEpochMilli();
    }

    /** 自旋等待直到系统时间推进到目标毫秒。 */
    private long waitUntil(long targetTs) {
        long now;
        do {
            now = currentTimeMillis();
        } while (now < targetTs);
        return now;
    }
}
