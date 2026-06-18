package com.lx.governance.agentplatform.model;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台级工具定义。
 * <p>
 * 描述一个平台工具的 schema、可靠性和访问控制信息。
 */
@Value
@Builder(toBuilder = true)
public class ToolDefinition {

    /** 工具名称。 */
    String toolName;

    /** 工具描述。 */
    String description;

    /** 入参 schema。 */
    Map<String, Object> schema;

    /** 风险等级。 */
    String riskLevel;

    /** 所有者。 */
    String owner;

    /** 超时时间。 */
    long timeoutMs;

    /** 重试策略。 */
    ToolRetryPolicy retryPolicy;

    /** 幂等策略。 */
    ToolIdempotencyPolicy idempotencyPolicy;

    /** 是否开启审计。 */
    @Builder.Default
    boolean auditEnabled = true;

    /** 可见灰度分组。 */
    @Builder.Default
    List<String> visibleGroupIds = List.of();

    /** 可见用户。 */
    @Builder.Default
    List<String> visibleUserIds = List.of();

    /** 可见租户。 */
    @Builder.Default
    List<String> visibleTenantIds = List.of();

    /** QPS 限制。 */
    @Builder.Default
    int qpsLimit = 0;

    /** 熔断失败阈值。 */
    @Builder.Default
    int circuitBreakerFailureThreshold = 0;

    /** 熔断打开时长。 */
    @Builder.Default
    long circuitOpenMillis = 0L;
}
