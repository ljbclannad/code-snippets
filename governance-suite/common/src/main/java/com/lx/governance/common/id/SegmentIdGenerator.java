package com.lx.governance.common.id;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ConcurrentModificationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于数据库号段的分布式 ID 生成器。
 *
 * <h3>核心特性</h3>
 * <ul>
 *   <li>双 buffer 预加载：当前号段使用到 70% 时异步加载下一个号段</li>
 *   <li>线程安全：使用 AtomicReference 和 ReentrantLock 保证并发安全</li>
 *   <li>乐观锁：数据库更新使用 WHERE max_id = ? 防止并发冲突</li>
 *   <li>自动重试：号段分配冲突时自动重试（最多 3 次）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * SegmentIdGenerator generator = new SegmentIdGenerator("order_no", repository);
 * long orderId = generator.nextId();
 * </pre>
 */
public class SegmentIdGenerator implements IdGenerator {
    private static final Logger log = LoggerFactory.getLogger(SegmentIdGenerator.class);
    private static final double DEFAULT_LOAD_THRESHOLD = 0.7;
    private static final int MAX_RETRY = 3;

    private final String bizTag;
    private final IdSegmentRepository repository;
    private final AtomicReference<SegmentBuffer> bufferRef;
    private final ExecutorService asyncLoader;
    private final double loadThreshold;

    public SegmentIdGenerator(String bizTag, IdSegmentRepository repository) {
        this(bizTag, repository, DEFAULT_LOAD_THRESHOLD);
    }

    public SegmentIdGenerator(String bizTag, IdSegmentRepository repository, double loadThreshold) {
        if (bizTag == null || bizTag.isEmpty()) {
            throw new IllegalArgumentException("bizTag cannot be null or empty");
        }
        if (repository == null) {
            throw new IllegalArgumentException("repository cannot be null");
        }
        this.bizTag = bizTag;
        this.repository = repository;
        this.loadThreshold = loadThreshold;
        this.asyncLoader = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "segment-loader-" + bizTag);
            t.setDaemon(true);
            return t;
        });

        // 初始化：同步加载第一个号段
        Segment initialSegment = allocateSegmentWithRetry();
        this.bufferRef = new AtomicReference<>(new SegmentBuffer(initialSegment, loadThreshold));

        log.info("SegmentIdGenerator initialized for bizTag={}, initial segment: {}",
                 bizTag, initialSegment);
    }

    /**
     * 生成下一个全局唯一 ID。
     *
     * @return 64 位正整数 ID
     * @throws IllegalStateException 如果无法获取新号段
     */
    public long nextId() {
        while (true) {
            SegmentBuffer buffer = bufferRef.get();

            // 检查是否需要预加载下一个号段
            if (buffer.needLoadNext()) {
                triggerAsyncLoad(buffer);
            }

            // 尝试从当前号段获取 ID
            long id = buffer.nextId();
            if (id > 0) {
                return id;
            }

            // 当前号段耗尽，切换到下一个号段
            switchToNextSegment(buffer);
        }
    }

    /**
     * 生成下一个 ID 并以字符串形式返回。
     */
    public String nextIdString() {
        return Long.toString(nextId());
    }

    /**
     * 触发异步加载下一个号段。
     */
    private void triggerAsyncLoad(SegmentBuffer buffer) {
        if (!buffer.markLoadingNext()) {
            return; // 已经在加载中
        }

        asyncLoader.submit(() -> {
            try {
                Segment nextSegment = allocateSegmentWithRetry();
                buffer.setNextSegment(nextSegment);
                log.info("Async loaded next segment for bizTag={}: {}", bizTag, nextSegment);
            } catch (Exception e) {
                log.error("Failed to load next segment for bizTag={}", bizTag, e);
                buffer.resetLoadingState();
            }
        });
    }

    /**
     * 切换到下一个号段。
     * 如果下一个号段未准备好，同步加载一个新号段。
     */
    private void switchToNextSegment(SegmentBuffer currentBuffer) {
        if (currentBuffer.hasNextSegment()) {
            // 下一个号段已准备好，直接切换
            currentBuffer.switchSegment();
            log.info("Switched to next segment for bizTag={}: {}",
                     bizTag, currentBuffer.getCurrentSegment());
        } else {
            // 下一个号段未准备好，同步加载（降级处理）
            log.warn("Next segment not ready for bizTag={}, loading synchronously", bizTag);
            Segment newSegment = allocateSegmentWithRetry();
            SegmentBuffer newBuffer = new SegmentBuffer(newSegment, loadThreshold);
            bufferRef.set(newBuffer);
            log.info("Created new buffer for bizTag={}: {}", bizTag, newSegment);
        }
    }

    /**
     * 分配号段，失败时自动重试。
     */
    private Segment allocateSegmentWithRetry() {
        Exception lastException = null;
        for (int i = 0; i < MAX_RETRY; i++) {
            try {
                return repository.allocateSegment(bizTag);
            } catch (ConcurrentModificationException e) {
                lastException = e;
                log.warn("Segment allocation conflict for bizTag={}, retry {}/{}",
                         bizTag, i + 1, MAX_RETRY);
                try {
                    Thread.sleep(10 * (i + 1)); // 递增退避
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while retrying segment allocation", ie);
                }
            }
        }
        throw new IllegalStateException(
            "Failed to allocate segment for bizTag=" + bizTag + " after " + MAX_RETRY + " retries",
            lastException);
    }

    /**
     * 关闭生成器，释放资源。
     */
    public void shutdown() {
        asyncLoader.shutdown();
        log.info("SegmentIdGenerator shutdown for bizTag={}", bizTag);
    }

    public String getBizTag() {
        return bizTag;
    }

    public SegmentBuffer getCurrentBuffer() {
        return bufferRef.get();
    }
}
