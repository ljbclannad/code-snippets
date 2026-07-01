package com.lx.governance.aichat.metrics;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerReason;

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
    private final Counter agentUpgradeTriggeredCounter;
    private final ConcurrentMap<String, Counter> rewriteCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> taskTriggerRouteCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> taskTriggerReasonCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> agentInvocationOutcomeCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> patientSafetyCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> agentFallbackCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> humanReviewCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> routeResultCounters = new ConcurrentHashMap<>();

    // 计时器
    private final Timer chatResponseTimer;
    private final Timer contextAnalysisTimer;
    private final Timer profileFetchTimer;
    private final Timer memoryRetrievalTimer;
    private final Timer embeddingTimer;
    private final ConcurrentMap<String, Timer> rewriteTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> agentConversationTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> stageTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> routeResultTimers = new ConcurrentHashMap<>();

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

        this.agentUpgradeTriggeredCounter = Counter.builder("ai.agent.upgrade.triggered")
                .description("Agent upgrade triggered count")
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

    public void recordAgentUpgradeTriggered() {
        agentUpgradeTriggeredCounter.increment();
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

    public void recordTaskTriggerDecision(TaskTriggerDecision decision) {
        String route = "UNKNOWN";
        String taskType = "UNKNOWN";

        if (decision != null) {
            route = decision.getRoute() != null ? decision.getRoute().name() : route;
            taskType = decision.getTaskType() != null ? decision.getTaskType().name() : taskType;
        }

        final String safeRoute = route;
        final String safeTaskType = taskType;

        taskTriggerRouteCounters.computeIfAbsent(safeRoute + "|" + safeTaskType, key -> Counter.builder("ai.task.trigger.decisions")
                .description("Task trigger decisions by route and task type")
                .tag("route", safeRoute)
                .tag("task_type", safeTaskType)
                .register(meterRegistry))
                .increment();

        if (decision == null || decision.getReasons() == null || decision.getReasons().isEmpty()) {
            recordTaskTriggerReason(safeRoute, safeTaskType, "UNKNOWN");
            return;
        }

        for (TaskTriggerReason reason : decision.getReasons()) {
            String safeReason = reason != null ? reason.name() : "UNKNOWN";
            recordTaskTriggerReason(safeRoute, safeTaskType, safeReason);
        }
    }

    private void recordTaskTriggerReason(String route, String taskType, String reason) {
        taskTriggerReasonCounters.computeIfAbsent(route + "|" + taskType + "|" + reason,
                key -> Counter.builder("ai.task.trigger.reasons")
                        .description("Task trigger reason hits by route, task type and reason")
                        .tag("route", route)
                        .tag("task_type", taskType)
                        .tag("reason", reason)
                        .register(meterRegistry))
                .increment();
    }

    public void recordAgentInvocation(String taskType, String outcome, long durationMs) {
        String safeTaskType = taskType == null || taskType.isBlank() ? "UNKNOWN" : taskType;
        String safeOutcome = outcome == null || outcome.isBlank() ? "UNKNOWN" : outcome;

        agentInvocationOutcomeCounters.computeIfAbsent(safeTaskType + "|" + safeOutcome,
                key -> Counter.builder("ai.agent.invocations")
                        .description("Agent invocation outcome count by task type")
                        .tag("task_type", safeTaskType)
                        .tag("outcome", safeOutcome)
                        .register(meterRegistry))
                .increment();

        agentConversationTimers.computeIfAbsent(safeOutcome,
                key -> Timer.builder("ai.agent.response.time")
                        .description("Agent invocation time by outcome")
                        .tag("outcome", safeOutcome)
                        .register(meterRegistry))
                .record(java.time.Duration.ofMillis(Math.max(durationMs, 0L)));
    }

    public void recordConversationPathLatency(String path, long durationMs) {
        String safePath = path == null || path.isBlank() ? "unknown" : path;
        agentConversationTimers.computeIfAbsent("path:" + safePath,
                key -> Timer.builder("ai.conversation.path.time")
                        .description("Conversation latency by path")
                        .tag("path", safePath)
                        .register(meterRegistry))
                .record(java.time.Duration.ofMillis(Math.max(durationMs, 0L)));
    }

    public void recordStageLatency(String stage, long durationMs) {
        String safeStage = stage == null || stage.isBlank() ? "unknown" : stage;
        stageTimers.computeIfAbsent(safeStage,
                key -> Timer.builder("ai.chat.stage.time")
                        .description("Main chat pipeline stage latency")
                        .tag("stage", safeStage)
                        .register(meterRegistry))
                .record(java.time.Duration.ofMillis(Math.max(durationMs, 0L)));
    }

    public void recordRouteResult(String routeResult, boolean degraded, long durationMs) {
        String safeRouteResult = routeResult == null || routeResult.isBlank() ? "unknown" : routeResult;
        String degradedTag = degraded ? "true" : "false";

        routeResultCounters.computeIfAbsent(safeRouteResult + "|" + degradedTag,
                key -> Counter.builder("ai.chat.route.results")
                        .description("Final chat route result count")
                        .tag("route_result", safeRouteResult)
                        .tag("degraded", degradedTag)
                        .register(meterRegistry))
                .increment();

        routeResultTimers.computeIfAbsent(safeRouteResult + "|" + degradedTag,
                key -> Timer.builder("ai.chat.route.time")
                        .description("Final chat route latency by route result")
                        .tag("route_result", safeRouteResult)
                        .tag("degraded", degradedTag)
                        .register(meterRegistry))
                .record(java.time.Duration.ofMillis(Math.max(durationMs, 0L)));
    }

    public void recordPatientSafetyInterception(String category) {
        String safeCategory = category == null || category.isBlank() ? "UNKNOWN" : category;
        patientSafetyCounters.computeIfAbsent(safeCategory,
                key -> Counter.builder("ai.patient.safety.interceptions")
                        .description("Patient safety interceptions by category")
                        .tag("category", safeCategory)
                        .register(meterRegistry))
                .increment();
    }

    public void recordAgentFallback(String reason) {
        String safeReason = reason == null || reason.isBlank() ? "UNKNOWN" : reason;
        agentFallbackCounters.computeIfAbsent(safeReason,
                key -> Counter.builder("ai.agent.fallbacks")
                        .description("Agent fallback count by reason")
                        .tag("reason", safeReason)
                        .register(meterRegistry))
                .increment();
    }

    public void recordHumanReviewRequest(String taskType) {
        String safeTaskType = taskType == null || taskType.isBlank() ? "UNKNOWN" : taskType;
        humanReviewCounters.computeIfAbsent(safeTaskType,
                key -> Counter.builder("ai.agent.human.review.requests")
                        .description("Human review requests by task type")
                        .tag("task_type", safeTaskType)
                        .register(meterRegistry))
                .increment();
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
