package com.health.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 向量检索监控指标
 */
@Slf4j
@Component
public class VectorMetrics {

    private final MeterRegistry meterRegistry;

    public VectorMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录向量检索请求
     */
    public void recordSearchRequest(int topK) {
        Counter.builder("vector.search.requests")
            .description("向量检索请求总数")
            .register(meterRegistry)
            .increment();

        // 记录 topK 分布
        meterRegistry.summary("vector.search.topk")
            .record(topK);
    }

    /**
     * 记录向量检索成功
     */
    public void recordSearchSuccess(int resultCount, long durationMs) {
        Counter.builder("vector.search.success")
            .description("向量检索成功次数")
            .register(meterRegistry)
            .increment();

        recordSearchDuration("success", durationMs);

        // 结果数量分布
        meterRegistry.summary("vector.search.results")
            .record(resultCount);

        log.debug("向量检索成功: results={}, duration={}ms", resultCount, durationMs);
    }

    /**
     * 记录向量检索失败
     */
    public void recordSearchFailure(String errorType) {
        recordSearchFailure(errorType, 0L);
    }

    public void recordSearchFailure(String errorType, long durationMs) {
        Counter.builder("vector.search.failure")
            .tag("error_type", errorType)
            .description("向量检索失败次数")
            .register(meterRegistry)
            .increment();

        recordSearchDuration("failure", durationMs);

        log.debug("向量检索失败: errorType={}", errorType);
    }

    /**
     * 记录向量插入
     */
    public void recordInsert(int count, long durationMs) {
        Counter.builder("vector.insert.total")
            .description("向量插入总数")
            .register(meterRegistry)
            .increment(count);

        Timer.builder("vector.insert.duration")
            .description("向量插入耗时")
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);

        log.debug("向量插入: count={}, duration={}ms", count, durationMs);
    }

    /**
     * 记录 Embedding 生成
     */
    public void recordEmbeddingGeneration(long durationMs, boolean isMock) {
        Counter.builder("vector.embedding.generated")
            .tag("mode", isMock ? "mock" : "real")
            .description("Embedding 生成次数")
            .register(meterRegistry)
            .increment();

        Timer.builder("vector.embedding.duration")
            .tag("mode", isMock ? "mock" : "real")
            .description("Embedding 生成耗时")
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录缓存命中
     */
    public void recordCacheHit() {
        Counter.builder("vector.cache.hits")
            .description("向量检索缓存命中次数")
            .register(meterRegistry)
            .increment();
    }

    /**
     * 记录缓存未命中
     */
    public void recordCacheMiss() {
        Counter.builder("vector.cache.misses")
            .description("向量检索缓存未命中次数")
            .register(meterRegistry)
            .increment();
    }

    private void recordSearchDuration(String outcome, long durationMs) {
        if (durationMs < 0) {
            return;
        }

        Timer.builder("vector.search.duration")
            .tag("outcome", outcome)
            .description("向量检索耗时")
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }
}
