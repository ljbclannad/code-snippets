package com.lx.governance.agentplatform.runtime;

import java.time.Duration;

import com.lx.governance.agentplatform.model.PlatformCostScope;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.model.RunType;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 平台统一指标服务。
 */
public class PlatformMetricsService {

    private final MeterRegistry meterRegistry;

    public PlatformMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRunLifecycle(
            String agentId,
            RunType runType,
            RunStatus runStatus,
            boolean degraded,
            boolean requiresApproval,
            String fallbackReason,
            long durationMs) {
        String safeAgentId = safeTag(agentId);
        String safeRunType = runType != null ? runType.name() : "UNKNOWN";
        String safeStatus = runStatus != null ? runStatus.name() : "UNKNOWN";
        String outcome = resolveOutcome(runStatus, degraded);

        Counter.builder("platform.run.total")
                .tag("agent_id", safeAgentId)
                .tag("run_type", safeRunType)
                .description("平台 run 总量")
                .register(meterRegistry)
                .increment();

        Counter.builder("platform.run.outcome")
                .tag("agent_id", safeAgentId)
                .tag("run_type", safeRunType)
                .tag("outcome", outcome)
                .description("平台 run 结果分布")
                .register(meterRegistry)
                .increment();

        Counter.builder("platform.run.status")
                .tag("agent_id", safeAgentId)
                .tag("status", safeStatus)
                .description("平台 run 状态分布")
                .register(meterRegistry)
                .increment();

        if (requiresApproval) {
            Counter.builder("platform.run.approval.required")
                    .tag("agent_id", safeAgentId)
                    .tag("run_type", safeRunType)
                    .description("平台 run 进入审批次数")
                    .register(meterRegistry)
                    .increment();
        }

        if (fallbackReason != null && !fallbackReason.isBlank()) {
            Counter.builder("platform.run.fallback")
                    .tag("agent_id", safeAgentId)
                    .tag("reason", fallbackReason)
                    .description("平台 run fallback 次数")
                    .register(meterRegistry)
                    .increment();
        }

        if (durationMs >= 0L) {
            Timer.builder("platform.run.latency")
                    .tag("agent_id", safeAgentId)
                    .tag("run_type", safeRunType)
                    .tag("outcome", outcome)
                    .description("平台 run 执行耗时")
                    .register(meterRegistry)
                    .record(Duration.ofMillis(durationMs));
        }
    }

    public void recordToolLatency(String agentId, String toolName, long durationMs, String outcome) {
        if (durationMs < 0L) {
            return;
        }
        Timer.builder("platform.tool.latency")
                .tag("agent_id", safeTag(agentId))
                .tag("tool_name", safeTag(toolName))
                .tag("outcome", safeTag(outcome))
                .description("平台工具调用耗时")
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    public void recordTokenCost(
            PlatformCostScope scope,
            String agentId,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            double estimatedCost) {
        String safeScope = scope != null ? scope.name() : "UNKNOWN";
        String safeAgentId = safeTag(agentId);

        DistributionSummary.builder("platform.tokens.prompt")
                .tag("scope", safeScope)
                .tag("agent_id", safeAgentId)
                .description("平台 prompt token 使用量")
                .register(meterRegistry)
                .record(Math.max(0, promptTokens));

        DistributionSummary.builder("platform.tokens.completion")
                .tag("scope", safeScope)
                .tag("agent_id", safeAgentId)
                .description("平台 completion token 使用量")
                .register(meterRegistry)
                .record(Math.max(0, completionTokens));

        DistributionSummary.builder("platform.tokens.total")
                .tag("scope", safeScope)
                .tag("agent_id", safeAgentId)
                .description("平台 total token 使用量")
                .register(meterRegistry)
                .record(Math.max(0, totalTokens));

        DistributionSummary.builder("platform.cost.estimated")
                .tag("scope", safeScope)
                .tag("agent_id", safeAgentId)
                .description("平台估算成本")
                .register(meterRegistry)
                .record(Math.max(0D, estimatedCost));
    }

    private String resolveOutcome(RunStatus runStatus, boolean degraded) {
        if (degraded) {
            return "degraded";
        }
        if (runStatus == null) {
            return "unknown";
        }
        return switch (runStatus) {
            case COMPLETED -> "success";
            case FAILED -> "failure";
            case WAITING_APPROVAL -> "pending_approval";
            default -> runStatus.name().toLowerCase();
        };
    }

    private String safeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
