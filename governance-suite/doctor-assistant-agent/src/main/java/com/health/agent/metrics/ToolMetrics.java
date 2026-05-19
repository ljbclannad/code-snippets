package com.health.agent.metrics;

import com.health.agent.tool.ToolRiskLevel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 工具调用监控指标
 */
@Slf4j
@Component
public class ToolMetrics {

    private final MeterRegistry meterRegistry;

    public ToolMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录工具调用
     */
    public void recordToolCall(String toolName, ToolRiskLevel riskLevel) {
        // 总调用次数
        Counter.builder("tool.calls.total")
            .tag("tool", toolName)
            .tag("risk_level", riskLevel.name())
            .description("工具调用总次数")
            .register(meterRegistry)
            .increment();

        log.debug("工具调用: tool={}, riskLevel={}", toolName, riskLevel);
    }

    /**
     * 记录工具执行成功
     */
    public void recordToolSuccess(String toolName, long durationMs) {
        // 成功次数
        Counter.builder("tool.calls.success")
            .tag("tool", toolName)
            .description("工具执行成功次数")
            .register(meterRegistry)
            .increment();

        // 执行耗时
        Timer.builder("tool.execution.duration")
            .tag("tool", toolName)
            .description("工具执行耗时")
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);

        log.debug("工具执行成功: tool={}, duration={}ms", toolName, durationMs);
    }

    /**
     * 记录工具执行失败
     */
    public void recordToolFailure(String toolName, String errorType) {
        // 失败次数
        Counter.builder("tool.calls.failure")
            .tag("tool", toolName)
            .tag("error_type", errorType)
            .description("工具执行失败次数")
            .register(meterRegistry)
            .increment();

        log.debug("工具执行失败: tool={}, errorType={}", toolName, errorType);
    }

    /**
     * 记录工具需要审批
     */
    public void recordToolNeedsApproval(String toolName) {
        Counter.builder("tool.calls.needs_approval")
            .tag("tool", toolName)
            .description("工具需要审批次数")
            .register(meterRegistry)
            .increment();

        log.debug("工具需要审批: tool={}", toolName);
    }

    /**
     * 记录按风险等级的工具调用分布
     */
    public void recordToolCallByRiskLevel(ToolRiskLevel riskLevel) {
        Counter.builder("tool.calls.by_risk_level")
            .tag("risk_level", riskLevel.name())
            .description("按风险等级的工具调用次数")
            .register(meterRegistry)
            .increment();
    }

    /**
     * 记录工具调用频率（Top N）
     */
    public void recordToolPopularity(String toolName) {
        Counter.builder("tool.popularity")
            .tag("tool", toolName)
            .description("工具调用频率统计")
            .register(meterRegistry)
            .increment();
    }
}
