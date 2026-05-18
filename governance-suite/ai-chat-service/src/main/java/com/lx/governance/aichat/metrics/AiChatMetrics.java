package com.lx.governance.aichat.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@Slf4j
public class AiChatMetrics {

    private final MeterRegistry meterRegistry;

    // 计数器
    private final Counter chatRequestCounter;
    private final Counter contextCacheHitCounter;
    private final Counter contextCacheMissCounter;
    private final Counter profileCacheHitCounter;
    private final Counter profileCacheMissCounter;
    private final Counter memoryDuplicateCounter;
    private final Counter budgetCompressionCounter;

    // 计时器
    private final Timer chatResponseTimer;
    private final Timer contextAnalysisTimer;
    private final Timer profileFetchTimer;
    private final Timer memoryRetrievalTimer;
    private final Timer embeddingTimer;

    public AiChatMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // 初始化计数器
        this.chatRequestCounter = Counter.builder("ai.chat.requests")
            .description("Total chat requests")
            .register(meterRegistry);

        this.contextCacheHitCounter = Counter.builder("ai.context.cache.hit")
            .description("Context cache hits")
            .register(meterRegistry);

        this.contextCacheMissCounter = Counter.builder("ai.context.cache.miss")
            .description("Context cache misses")
            .register(meterRegistry);

        this.profileCacheHitCounter = Counter.builder("ai.profile.cache.hit")
            .description("Profile cache hits")
            .register(meterRegistry);

        this.profileCacheMissCounter = Counter.builder("ai.profile.cache.miss")
            .description("Profile cache misses")
            .register(meterRegistry);

        this.memoryDuplicateCounter = Counter.builder("ai.memory.duplicate")
            .description("Duplicate memories filtered")
            .register(meterRegistry);

        this.budgetCompressionCounter = Counter.builder("ai.budget.compression")
            .description("Budget compression triggered")
            .register(meterRegistry);

        // 初始化计时器
        this.chatResponseTimer = Timer.builder("ai.chat.response.time")
            .description("Chat response time")
            .register(meterRegistry);

        this.contextAnalysisTimer = Timer.builder("ai.context.analysis.time")
            .description("Context analysis time")
            .register(meterRegistry);

        this.profileFetchTimer = Timer.builder("ai.profile.fetch.time")
            .description("Profile fetch time")
            .register(meterRegistry);

        this.memoryRetrievalTimer = Timer.builder("ai.memory.retrieval.time")
            .description("Memory retrieval time")
            .register(meterRegistry);

        this.embeddingTimer = Timer.builder("ai.embedding.time")
            .description("Embedding generation time")
            .register(meterRegistry);
    }

    // 计数器方法
    public void recordChatRequest() {
        chatRequestCounter.increment();
    }

    public void recordContextCacheHit() {
        contextCacheHitCounter.increment();
    }

    public void recordContextCacheMiss() {
        contextCacheMissCounter.increment();
    }

    public void recordProfileCacheHit() {
        profileCacheHitCounter.increment();
    }

    public void recordProfileCacheMiss() {
        profileCacheMissCounter.increment();
    }

    public void recordMemoryDuplicate() {
        memoryDuplicateCounter.increment();
    }

    public void recordBudgetCompression() {
        budgetCompressionCounter.increment();
    }

    // 计时器方法
    public <T> T recordChatResponse(Supplier<T> operation) {
        return chatResponseTimer.record(operation);
    }

    public <T> T recordContextAnalysis(Supplier<T> operation) {
        return contextAnalysisTimer.record(operation);
    }

    public <T> T recordProfileFetch(Supplier<T> operation) {
        return profileFetchTimer.record(operation);
    }

    public <T> T recordMemoryRetrieval(Supplier<T> operation) {
        return memoryRetrievalTimer.record(operation);
    }

    public <T> T recordEmbedding(Supplier<T> operation) {
        return embeddingTimer.record(operation);
    }
}
