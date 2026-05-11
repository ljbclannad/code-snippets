package com.lx.governance.common.id;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 双 buffer 号段管理器。
 * 维护两个号段：当前使用的号段和预加载的备用号段。
 * 当当前号段使用到阈值时触发异步加载下一个号段，当前号段耗尽时切换到备用号段。
 */
public class SegmentBuffer {
    private volatile Segment currentSegment;
    private volatile Segment nextSegment;
    private volatile boolean isLoadingNext;
    private final ReentrantLock switchLock;
    private final double loadThreshold;

    public SegmentBuffer(Segment initialSegment, double loadThreshold) {
        if (initialSegment == null) {
            throw new IllegalArgumentException("initialSegment cannot be null");
        }
        if (loadThreshold <= 0 || loadThreshold >= 1) {
            throw new IllegalArgumentException("loadThreshold must be in (0, 1)");
        }
        this.currentSegment = initialSegment;
        this.nextSegment = null;
        this.isLoadingNext = false;
        this.switchLock = new ReentrantLock();
        this.loadThreshold = loadThreshold;
    }

    /**
     * 从当前号段获取下一个ID。
     *
     * @return 如果当前号段未耗尽返回有效ID，否则返回 -1 表示需要切换
     */
    public long nextId() {
        return currentSegment.nextId();
    }

    /**
     * 检查是否需要预加载下一个号段。
     *
     * @return true 如果当前号段使用率超过阈值且下一个号段未在加载中
     */
    public boolean needLoadNext() {
        return !isLoadingNext
               && nextSegment == null
               && currentSegment.shouldLoadNext(loadThreshold);
    }

    /**
     * 标记开始加载下一个号段。
     *
     * @return true 如果成功标记，false 如果已经在加载中
     */
    public boolean markLoadingNext() {
        switchLock.lock();
        try {
            if (isLoadingNext || nextSegment != null) {
                return false;
            }
            isLoadingNext = true;
            return true;
        } finally {
            switchLock.unlock();
        }
    }

    /**
     * 设置预加载的下一个号段。
     *
     * @param segment 新加载的号段
     */
    public void setNextSegment(Segment segment) {
        switchLock.lock();
        try {
            this.nextSegment = segment;
            this.isLoadingNext = false;
        } finally {
            switchLock.unlock();
        }
    }

    /**
     * 加载失败时重置加载状态。
     */
    public void resetLoadingState() {
        switchLock.lock();
        try {
            this.isLoadingNext = false;
        } finally {
            switchLock.unlock();
        }
    }

    /**
     * 切换到下一个号段。
     * 如果下一个号段不可用，抛出异常。
     *
     * @throws IllegalStateException 如果下一个号段未准备好
     */
    public void switchSegment() {
        switchLock.lock();
        try {
            if (nextSegment == null) {
                throw new IllegalStateException(
                    "Cannot switch segment: next segment is not ready. Current: " + currentSegment);
            }
            this.currentSegment = nextSegment;
            this.nextSegment = null;
        } finally {
            switchLock.unlock();
        }
    }

    /**
     * 检查是否有可用的下一个号段。
     */
    public boolean hasNextSegment() {
        return nextSegment != null;
    }

    public Segment getCurrentSegment() {
        return currentSegment;
    }

    public Segment getNextSegment() {
        return nextSegment;
    }

    public boolean isLoadingNext() {
        return isLoadingNext;
    }

    @Override
    public String toString() {
        return String.format("SegmentBuffer[current=%s, next=%s, loading=%s]",
                currentSegment, nextSegment, isLoadingNext);
    }
}
