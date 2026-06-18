package com.lx.governance.agentplatform.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.lx.governance.agentplatform.model.PlatformDispatchContext;
import com.lx.governance.agentplatform.model.PlatformDispatchDecision;

/**
 * 平台治理层灰度/熔断/放行策略引擎。
 */
public class PlatformDispatchPolicyEngine {

    private final Set<String> enabledTaskTypes;
    private final Set<String> enabledGroupIds;
    private final Set<String> enabledUserIds;
    private final Set<String> enabledTenantIds;
    private final Set<String> enabledAgentVersions;
    private final boolean circuitBreakerOpen;

    public PlatformDispatchPolicyEngine(
            List<String> enabledTaskTypes,
            List<String> enabledGroupIds,
            List<String> enabledUserIds,
            List<String> enabledTenantIds,
            List<String> enabledAgentVersions,
            boolean circuitBreakerOpen) {
        this.enabledTaskTypes = new HashSet<>(safe(enabledTaskTypes));
        this.enabledGroupIds = new HashSet<>(safe(enabledGroupIds));
        this.enabledUserIds = new HashSet<>(safe(enabledUserIds));
        this.enabledTenantIds = new HashSet<>(safe(enabledTenantIds));
        this.enabledAgentVersions = new HashSet<>(safe(enabledAgentVersions));
        this.circuitBreakerOpen = circuitBreakerOpen;
    }

    public PlatformDispatchDecision evaluate(PlatformDispatchContext context) {
        if (circuitBreakerOpen) {
            return decision(false, "agent_circuit_breaker_open");
        }
        String taskType = context != null ? context.getTaskType() : null;
        if (!enabledTaskTypes.isEmpty() && !enabledTaskTypes.contains(defaultValue(taskType, "NONE"))) {
            return decision(false, "task_type_not_gray_enabled");
        }
        if (!matchesScope(enabledGroupIds, context != null ? context.getGroupId() : null)) {
            return decision(false, "group_not_gray_enabled");
        }
        if (!matchesScope(enabledUserIds, context != null ? context.getUserId() : null)) {
            return decision(false, "user_not_gray_enabled");
        }
        if (!matchesScope(enabledTenantIds, context != null ? context.getTenantId() : null)) {
            return decision(false, "tenant_not_gray_enabled");
        }
        if (!matchesScope(enabledAgentVersions, context != null ? context.getAgentVersion() : null)) {
            return decision(false, "agent_version_not_gray_enabled");
        }
        return decision(true, "gray_enabled");
    }

    private PlatformDispatchDecision decision(boolean allowed, String reason) {
        return PlatformDispatchDecision.builder()
                .allowed(allowed)
                .reason(reason)
                .build();
    }

    private boolean matchesScope(Set<String> enabledValues, String actualValue) {
        if (enabledValues.isEmpty()) {
            return true;
        }
        if (actualValue == null || actualValue.isBlank()) {
            return false;
        }
        return enabledValues.contains(actualValue);
    }

    private List<String> safe(List<String> values) {
        return values != null ? values : List.of();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
