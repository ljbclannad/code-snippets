package com.health.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.DistributionSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Agent 监控指标
 */
@Slf4j
@Component
public class AgentMetrics {

    private final MeterRegistry meterRegistry;

    // 计数器
    private final Counter executionTotal;
    private final Counter executionSuccess;
    private final Counter executionFailure;
    private final Counter executionPendingApproval;
    private final Counter executionMaxSteps;
    private final Counter duplicateToolSuppressed;
    private final Counter approvalApproved;
    private final Counter approvalRejected;
    private final Set<String> boundExecutors = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Counter> executorRejectedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> entryRejectedCounters = new ConcurrentHashMap<>();

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // 初始化计数器
        this.executionTotal = Counter.builder("agent.execution.total")
            .description("Agent 总执行次数")
            .register(meterRegistry);

        this.executionSuccess = Counter.builder("agent.execution.success")
            .description("Agent 执行成功次数")
            .register(meterRegistry);

        this.executionFailure = Counter.builder("agent.execution.failure")
            .description("Agent 执行失败次数")
            .register(meterRegistry);

        this.executionPendingApproval = Counter.builder("agent.execution.pending_approval")
            .description("Agent 等待审批次数")
            .register(meterRegistry);

        this.executionMaxSteps = Counter.builder("agent.execution.max_steps")
            .description("Agent 达到最大步数次数")
            .register(meterRegistry);

        this.duplicateToolSuppressed = Counter.builder("agent.execution.duplicate_tool_suppressed")
            .description("Agent 抑制重复工具调用次数")
            .register(meterRegistry);

        this.approvalApproved = Counter.builder("agent.approval.approved")
            .description("Agent 任务审批通过次数")
            .register(meterRegistry);

        this.approvalRejected = Counter.builder("agent.approval.rejected")
            .description("Agent 任务审批驳回次数")
            .register(meterRegistry);

    }

    /**
     * 记录 Agent 执行开始
     */
    public void recordExecutionStart() {
        executionTotal.increment();
    }

    /**
     * 记录 Agent 执行成功
     */
    public void recordExecutionSuccess(int steps, long durationMs) {
        executionSuccess.increment();
        recordExecutionSample("success", steps, durationMs);

        log.debug("Agent 执行成功: steps={}, duration={}ms", steps, durationMs);
    }

    /**
     * 记录 Agent 执行失败
     */
    public void recordExecutionFailure(String errorType) {
        recordExecutionFailure(errorType, 0, 0L);
    }

    public void recordExecutionFailure(String errorType, int steps, long durationMs) {
        executionFailure.increment();
        recordExecutionSample("failure", steps, durationMs);

        // 按错误类型分类
        Counter.builder("agent.execution.failure.by_type")
            .tag("error_type", errorType)
            .register(meterRegistry)
            .increment();

        log.debug("Agent 执行失败: errorType={}", errorType);
    }

    /**
     * 记录 Agent 等待审批
     */
    public void recordPendingApproval(String toolName) {
        executionPendingApproval.increment();

        // 按工具分类
        Counter.builder("agent.pending_approval.by_tool")
            .tag("tool", toolName)
            .register(meterRegistry)
            .increment();

        log.debug("Agent 等待审批: tool={}", toolName);
    }

    public void recordApprovalDecision(String decision, String taskType) {
        String safeDecision = decision == null ? "unknown" : decision;
        String safeTaskType = taskType == null ? "unknown" : taskType;

        if ("approved".equalsIgnoreCase(safeDecision)) {
            approvalApproved.increment();
        } else if ("rejected".equalsIgnoreCase(safeDecision)) {
            approvalRejected.increment();
        }

        Counter.builder("agent.approval.by_decision")
            .tag("decision", safeDecision)
            .tag("task_type", safeTaskType)
            .description("Agent 审批结果分布")
            .register(meterRegistry)
            .increment();
    }

    /**
     * 记录 Agent 达到最大步数
     */
    public void recordMaxStepsReached(int maxSteps) {
        recordMaxStepsReached(maxSteps, 0L);
    }

    public void recordMaxStepsReached(int maxSteps, long durationMs) {
        executionMaxSteps.increment();
        recordExecutionSample("max_steps", maxSteps, durationMs);

        log.warn("Agent 达到最大步数: maxSteps={}", maxSteps);
    }

    public void recordDuplicateToolSuppression(String toolName) {
        duplicateToolSuppressed.increment();
        Counter.builder("agent.execution.duplicate_tool_suppressed.by_tool")
            .tag("tool", toolName == null ? "unknown" : toolName)
            .description("按工具统计的重复调用抑制次数")
            .register(meterRegistry)
            .increment();
    }

    /**
     * 记录 Token 使用量
     */
    public void recordTokenUsage(int promptTokens, int completionTokens, int totalTokens) {
        meterRegistry.counter("agent.tokens.prompt").increment(promptTokens);
        meterRegistry.counter("agent.tokens.completion").increment(completionTokens);
        meterRegistry.counter("agent.tokens.total").increment(totalTokens);

        log.debug("Token 使用: prompt={}, completion={}, total={}",
            promptTokens, completionTokens, totalTokens);
    }

    /**
     * 记录会话状态
     */
    public void recordSessionStatus(String status) {
        Counter.builder("agent.sessions.by_status")
            .tag("status", status)
            .description("按状态统计的会话数")
            .register(meterRegistry)
            .increment();
    }

    public void recordKnowledgeRetrieval(String source, String outcome, long durationMs, int resultCount) {
        String safeSource = source == null || source.isBlank() ? "unknown" : source;
        String safeOutcome = outcome == null || outcome.isBlank() ? "unknown" : outcome;

        Counter.builder("agent.kb.retrieval.total")
            .description("Knowledge retrieval attempts by source and outcome")
            .tag("source", safeSource)
            .tag("outcome", safeOutcome)
            .register(meterRegistry)
            .increment();

        if (durationMs >= 0) {
            Timer.builder("agent.kb.retrieval.duration")
                .description("Knowledge retrieval duration")
                .tag("source", safeSource)
                .tag("outcome", safeOutcome)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        }

        DistributionSummary.builder("agent.kb.retrieval.results")
            .description("Knowledge retrieval result count")
            .baseUnit("items")
            .tag("source", safeSource)
            .tag("outcome", safeOutcome)
            .register(meterRegistry)
            .record(Math.max(resultCount, 0));
    }

    public void recordEvidenceUsage(String stage, String taskType, int evidenceCount, boolean blocked) {
        String safeStage = stage == null || stage.isBlank() ? "unknown" : stage;
        String safeTaskType = taskType == null || taskType.isBlank() ? "unknown" : taskType;
        String evidenceState = evidenceCount > 0 ? "present" : "missing";

        Counter.builder("agent.evidence.usage.total")
            .description("Evidence usage distribution by stage")
            .tag("stage", safeStage)
            .tag("task_type", safeTaskType)
            .tag("evidence_state", evidenceState)
            .tag("blocked", Boolean.toString(blocked))
            .register(meterRegistry)
            .increment();

        DistributionSummary.builder("agent.evidence.count")
            .description("Evidence item count by stage")
            .baseUnit("items")
            .tag("stage", safeStage)
            .tag("task_type", safeTaskType)
            .tag("blocked", Boolean.toString(blocked))
            .register(meterRegistry)
            .record(Math.max(evidenceCount, 0));
    }

    public void bindExecutor(String executorName, ThreadPoolExecutor executor) {
        if (executorName == null || executorName.isBlank() || executor == null || !boundExecutors.add(executorName)) {
            return;
        }

        Gauge.builder("agent.executor.active", executor, ThreadPoolExecutor::getActiveCount)
            .description("Agent executor active thread count")
            .tag("executor", executorName)
            .register(meterRegistry);
        Gauge.builder("agent.executor.pool.size", executor, ThreadPoolExecutor::getPoolSize)
            .description("Agent executor current pool size")
            .tag("executor", executorName)
            .register(meterRegistry);
        Gauge.builder("agent.executor.max.pool.size", executor, ThreadPoolExecutor::getMaximumPoolSize)
            .description("Agent executor max pool size")
            .tag("executor", executorName)
            .register(meterRegistry);
        Gauge.builder("agent.executor.queue.size", executor, threadPoolExecutor -> threadPoolExecutor.getQueue().size())
            .description("Agent executor queue size")
            .tag("executor", executorName)
            .register(meterRegistry);
        Gauge.builder("agent.executor.queue.remaining", executor,
                threadPoolExecutor -> threadPoolExecutor.getQueue().remainingCapacity())
            .description("Agent executor remaining queue capacity")
            .tag("executor", executorName)
            .register(meterRegistry);
    }

    public void recordExecutorRejected(String executorName) {
        String safeExecutorName = executorName == null || executorName.isBlank() ? "unknown" : executorName;
        executorRejectedCounters.computeIfAbsent(safeExecutorName,
                key -> Counter.builder("agent.executor.rejected")
                    .description("Agent executor rejected submissions")
                    .tag("executor", safeExecutorName)
                    .register(meterRegistry))
            .increment();
    }

    public void recordEntryRejected(String entry, String reason, String disposition) {
        String safeEntry = entry == null || entry.isBlank() ? "unknown" : entry;
        String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;
        String safeDisposition = disposition == null || disposition.isBlank() ? "unknown" : disposition;
        String counterKey = safeEntry + "|" + safeReason + "|" + safeDisposition;
        entryRejectedCounters.computeIfAbsent(counterKey,
                key -> Counter.builder("agent.entry.rejected")
                    .description("Agent entry rejected requests")
                    .tag("entry", safeEntry)
                    .tag("reason", safeReason)
                    .tag("disposition", safeDisposition)
                    .register(meterRegistry))
            .increment();
    }

    private void recordExecutionSample(String outcome, int steps, long durationMs) {
        if (durationMs >= 0) {
            Timer.builder("agent.execution.duration")
                .tag("outcome", outcome)
                .description("Agent 执行耗时")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        }

        if (steps > 0) {
            meterRegistry.summary("agent.execution.steps")
                .record(steps);
        }
    }
}
