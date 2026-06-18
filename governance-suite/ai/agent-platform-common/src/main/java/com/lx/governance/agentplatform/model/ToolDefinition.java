package com.lx.governance.agentplatform.model;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台级工具定义。
 */
@Value
@Builder(toBuilder = true)
public class ToolDefinition {

    String toolName;

    String description;

    Map<String, Object> schema;

    String riskLevel;

    String owner;

    long timeoutMs;

    ToolRetryPolicy retryPolicy;

    ToolIdempotencyPolicy idempotencyPolicy;

    @Builder.Default
    boolean auditEnabled = true;

    @Builder.Default
    List<String> visibleGroupIds = List.of();

    @Builder.Default
    List<String> visibleUserIds = List.of();

    @Builder.Default
    List<String> visibleTenantIds = List.of();

    @Builder.Default
    int qpsLimit = 0;

    @Builder.Default
    int circuitBreakerFailureThreshold = 0;

    @Builder.Default
    long circuitOpenMillis = 0L;
}
