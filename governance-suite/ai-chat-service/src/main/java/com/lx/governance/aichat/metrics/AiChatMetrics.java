package com.lx.governance.aichat.metrics;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class AiChatMetrics {

    private final MeterRegistry meterRegistry;

    // 计数器
    private final Counter chatRequestCounter;
    private final Counter chatErrorCounter;
    private final Counter contextCacheHitCounter;
    private final Counter contextCacheMissCounter;
    private final Counter profileCacheHitCounter;
    private final Counter profileCacheMissCounter;
    private final Counter memoryDuplicateCounter;
    private final Counter budgetCompressionCounter;
    private final ConcurrentMap<String, Counter> rewriteCounters = new ConcurrentHashMap<>();

    // 计时器
    private final Timer chatResponseTimer;
    private final Timer contextAnalysisTimer;
    private final Timer profileFetchTimer;
    private final Timer memoryRetrievalTimer;
    private final Timer embeddingTimer;
    private final ConcurrentMap<String, Timer> rewriteTimers = new ConcurrentHashMap<>();

    // 降级相关指标
    private final io.micrometer.core.instrument.Gauge degradationLevelGauge;
    private final Counter degradationTriggerCounter;

    private volatile int currentDegradationLevel = 0; // 0=NORMAL, 1=LIGHT, ..., 5=CIRCUIT_BREAK

    public AiChatMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // 初始化计数器
        // Micrometer 会自动将 . 转换为 _，并为 Counter 类型添加 _total 后缀
        this.chatRequestCounter = Counter.builder("ai.chat.requests")
                .description("Total chat requests")
                .register(meterRegistry);

        this.chatErrorCounter = Counter.builder("ai.chat.errors")
                .description("Total chat errors")
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

        // 降级指标
        this.degradationLevelGauge = io.micrometer.core.instrument.Gauge.builder(
                "ai.degradation.level", this, AiChatMetrics::getCurrentDegradationLevel)
                .description(
                        "Current degradation level (0=NORMAL, 1=LIGHT, 2=MEDIUM, 3=HEAVY, 4=CRITICAL, 5=CIRCUIT_BREAK)")
                .register(meterRegistry);

        this.degradationTriggerCounter = Counter.builder("ai.degradation.triggers")
                .description("Degradation trigger count")
                .register(meterRegistry);
    }

    private double getCurrentDegradationLevel() {
        return currentDegradationLevel;
    }

    // 计数器方法
    public void recordChatRequest() {
        chatRequestCounter.increment();
    }

    public void recordChatError() {
        chatErrorCounter.increment();
    }

    public void recordDegradationTrigger() {
        degradationTriggerCounter.increment();
    }

    public void updateDegradationLevel(int level) {
        this.currentDegradationLevel = level;
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

    public void recordRewrite(String source, String reason, long durationMs) {
        String safeSource = source == null || source.isBlank() ? "UNKNOWN" : source;
        String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;

        rewriteCounters.computeIfAbsent(safeSource + "|" + safeReason, key -> Counter.builder("ai.rewrite.requests")
                .description("Retrieval rewrite requests by source and reason")
                .tag("source", safeSource)
                .tag("reason", safeReason)
                .register(meterRegistry))
                .increment();

        rewriteTimers.computeIfAbsent(safeSource, key -> Timer.builder("ai.rewrite.time")
                .description("Retrieval rewrite processing time by source")
                .tag("source", safeSource)
                .register(meterRegistry))
                .record(java.time.Duration.ofMillis(Math.max(durationMs, 0L)));
    }

    // 计时器方法
    public <T> T recordChatResponse(Supplier<T> operation) {
        return chatResponseTimer.record(operation);
    }

    public <T> CompletableFuture<T> recordChatResponseAsync(Supplier<CompletableFuture<T>> operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return operation.get().whenComplete((result, throwable) -> sample.stop(chatResponseTimer));
        } catch (Exception e) {
            sample.stop(chatResponseTimer);
            throw e;
        }
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
