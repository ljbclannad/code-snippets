package com.lx.governance.common.id;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 号段数据结构，表示一个连续的ID区间 [minId, maxId]。
 * 使用 AtomicLong 保证线程安全的ID分配。
 */
public class Segment {
    private final long minId;
    private final long maxId;
    private final AtomicLong current;
    private final int step;

    public Segment(long minId, long maxId, int step) {
        if (minId > maxId) {
            throw new IllegalArgumentException("minId must be <= maxId");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("step must be positive");
        }
        this.minId = minId;
        this.maxId = maxId;
        this.step = step;
        this.current = new AtomicLong(minId - 1);
    }

    /**
     * 获取下一个ID。
     *
     * @return 如果号段未耗尽返回有效ID，否则返回 -1
     */
    public long nextId() {
        long id = current.incrementAndGet();
        if (id > maxId) {
            return -1;
        }
        return id;
    }

    /**
     * 检查是否应该触发下一个号段的加载。
     *
     * @param threshold 阈值比例，如 0.7 表示使用到 70% 时触发
     * @return true 如果当前使用率超过阈值
     */
    public boolean shouldLoadNext(double threshold) {
        long curr = current.get();
        if (curr < minId) {
            return false;
        }
        long used = curr - minId + 1;
        return used >= (step * threshold);
    }

    /**
     * 检查号段是否已耗尽。
     */
    public boolean isExhausted() {
        return current.get() >= maxId;
    }

    public long getMinId() {
        return minId;
    }

    public long getMaxId() {
        return maxId;
    }

    public int getStep() {
        return step;
    }

    public long getCurrent() {
        return current.get();
    }

    @Override
    public String toString() {
        return String.format("Segment[%d-%d, current=%d, step=%d]",
                minId, maxId, current.get(), step);
    }
}
