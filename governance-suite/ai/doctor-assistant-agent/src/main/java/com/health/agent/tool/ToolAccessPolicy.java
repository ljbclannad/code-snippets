package com.health.agent.tool;

import java.util.List;

import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.ToolDefinition;

/**
 * 工具目录展示和调用共用的访问范围规则。
 */
public final class ToolAccessPolicy {

    private ToolAccessPolicy() {
    }

    public static boolean allows(ToolDefinition definition, AgentAccessContext context) {
        return definition == null
                || (matches(definition.getVisibleGroupIds(), context != null ? context.getGroupId() : null)
                && matches(definition.getVisibleUserIds(), context != null ? context.getUserId() : null)
                && matches(definition.getVisibleTenantIds(), context != null ? context.getTenantId() : null));
    }

    private static boolean matches(List<String> allowed, String value) {
        return allowed == null || allowed.isEmpty() || (value != null && !value.isBlank() && allowed.contains(value));
    }
}
