package com.example.idgendemo.id;

import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 号段发号（Leaf 思路的简化版）：
 * - current 段用完后切换到 next 段
 * - remaining 低于阈值时预取 next（可异步）
 *
 * 另外：
 * - 为了和 Snowflake 范围隔离：bit62 置 1（保持 bit63=0），避免两种策略碰撞
 */
public class SegmentIdGenerator implements IdGenerator {

    private static final long STRATEGY_BIT = 1L << 62; // bit62=1

    private final SegmentDao dao;
    private final String bizTag;
    private final long refillThreshold;
    private final boolean asyncPrefetch;
    private final IdGenMetrics metrics;

    private volatile Segment current;
    private volatile Segment next;
    private final ReentrantLock lock = new ReentrantLock();

    private final ExecutorService prefetchPool;

    public SegmentIdGenerator(SegmentDao dao, String bizTag, long refillThreshold, boolean asyncPrefetch, IdGenMetrics metrics) {
        this.dao = dao;
        this.bizTag = bizTag;
        this.refillThreshold = Math.max(1, refillThreshold);
        this.asyncPrefetch = asyncPrefetch;
        this.metrics = metrics;

        this.prefetchPool = asyncPrefetch
                ? Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "segment-prefetch");
                    t.setDaemon(true);
                    return t;
                })
                : null;
    }

    @Override
    public long nextId(String grayKey) {
        metrics.incRequest(strategyName());

        Segment seg = current;
        if (seg == null) {
            lock.lock();
            try {
                if (current == null) {
                    current = dao.allocate(bizTag);
                }
                seg = current;
            } finally {
                lock.unlock();
            }
        }

        long v = seg.next();
        if (v != -1) {
            maybePrefetch(seg);
            return STRATEGY_BIT | v;
        }

        // current 用尽：尝试切 next
        lock.lock();
        try {
            // double check：可能别的线程已经切过了
            if (current != seg) {
                long vv = current.next();
                if (vv != -1) return STRATEGY_BIT | vv;
            }

            if (next == null) {
                next = dao.allocate(bizTag);
            }
            current = next;
            next = null;

            long vv = current.next();
            if (vv == -1) throw new IllegalStateException("Segment allocation error");
            // 切换后也尝试预取
            maybePrefetch(current);
            return STRATEGY_BIT | vv;
        } finally {
            lock.unlock();
        }
    }

    private void maybePrefetch(Segment seg) {
        if (next != null) return;
        if (seg.remaining() >= refillThreshold) return;

        if (!asyncPrefetch) {
            // 同步预取（简单但可能增加尾延迟）
            lock.lock();
            try {
                if (next == null) next = dao.allocate(bizTag);
            } finally {
                lock.unlock();
            }
            return;
        }

        // 异步预取：避免在业务线程里卡 DB
        prefetchPool.submit(() -> {
            if (next != null) return;
            lock.lock();
            try {
                if (next == null) next = dao.allocate(bizTag);
            } finally {
                lock.unlock();
            }
        });
    }

    @Override
    public String strategyName() {
        return "SEGMENT";
    }

    public long currentRemaining() {
        Segment seg = current;
        return seg == null ? -1 : seg.remaining();
    }
}
