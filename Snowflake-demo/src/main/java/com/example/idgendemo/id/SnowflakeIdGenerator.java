package com.example.idgendemo.id;

import java.time.Instant;

public final class SnowflakeIdGenerator implements IdGenerator {

    /**
     * 最高位(bit63)保持 0，避免负数。
     * bit62 作为策略位：0=Snowflake，1=Segment（Segment 那边会置 1<<62）
     *
     * 因此 Snowflake 可用 payload 只有 62 bits。
     * payload bits = timestampBits(41) + workerIdBits(10) + sequenceBits(11) = 62
     */
    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 11;

    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;

    private final long workerId;
    private final long epoch;
    private final long maxBackwardMs;
    private final IdGenMetrics metrics;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long workerId, long epoch, long maxBackwardMs, IdGenMetrics metrics) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId out of range: 0~" + MAX_WORKER_ID + ", actual=" + workerId);
        }
        this.workerId = workerId;
        this.epoch = epoch;
        this.maxBackwardMs = Math.max(0, maxBackwardMs);
        this.metrics = metrics;
    }

    @Override
    public synchronized long nextId(String grayKey) {
        metrics.incRequest(strategyName());

        long now = currentTimeMillis();

        if (now < lastTimestamp) {
            long diff = lastTimestamp - now;
            metrics.clockBackwards.increment();
            if (diff <= maxBackwardMs) {
                now = waitUntil(lastTimestamp);
            } else {
                throw new IllegalStateException("Clock moved backwards. Refuse to generate id for " + diff + " ms");
            }
        }

        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 序列耗尽：等待下一毫秒
                metrics.sequenceOverflow.increment();
                now = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = now;

        long tsPart = (now - epoch) << TIMESTAMP_SHIFT;
        long widPart = workerId << WORKER_ID_SHIFT;
        long payload = tsPart | widPart | sequence;

        // bit62=0 表示 snowflake，天然就是 0，不需要额外处理
        return payload;
    }

    @Override
    public String strategyName() {
        return "SNOWFLAKE";
    }

    private long currentTimeMillis() {
        return Instant.now().toEpochMilli();
    }

    private long waitUntil(long target) {
        long now;
        do {
            now = currentTimeMillis();
        } while (now < target);
        return now;
    }

    public long getWorkerId() {
        return workerId;
    }
}
