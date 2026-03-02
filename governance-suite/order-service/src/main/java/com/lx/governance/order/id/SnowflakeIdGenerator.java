package com.lx.governance.order.id;

import java.time.Instant;

public final class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L;
    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;

    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;

    private final long workerId;
    private final long maxBackwardMs;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long workerId, long maxBackwardMs) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId out of range: 0~" + MAX_WORKER_ID);
        }
        this.workerId = workerId;
        this.maxBackwardMs = Math.max(0, maxBackwardMs);
    }

    public synchronized long nextId() {
        long now = currentTimeMillis();
        if (now < lastTimestamp) {
            long diff = lastTimestamp - now;
            if (diff <= maxBackwardMs) {
                now = waitUntil(lastTimestamp);
            } else {
                throw new IllegalStateException("Clock moved backwards. Refuse to generate id for " + diff + " ms");
            }
        }

        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                now = waitUntil(lastTimestamp + 1);
            }
        } else {
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

    private long waitUntil(long targetTs) {
        long now;
        do {
            now = currentTimeMillis();
        } while (now < targetTs);
        return now;
    }
}
