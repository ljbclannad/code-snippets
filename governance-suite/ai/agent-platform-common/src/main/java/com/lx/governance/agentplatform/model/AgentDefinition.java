package com.lx.governance.agentplatform.model;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台统一 Agent 定义。
 */
@Value
@Builder(toBuilder = true)
public class AgentDefinition {

    String agentId;

    String displayName;

    AgentType agentType;

    @Singular("capability")
    List<String> capabilities;

    @Singular("supportedTaskType")
    List<String> supportedTaskTypes;

    Map<String, Object> inputSchema;

    Map<String, Object> outputSchema;

    @Singular("allowedTool")
    List<String> allowedTools;

    String riskLevel;

    String owner;

    String version;

    @Singular("visibleGroupId")
    List<String> visibleGroupIds;

    @Singular("visibleUserId")
    List<String> visibleUserIds;

    @Singular("visibleTenantId")
    List<String> visibleTenantIds;

    AgentStatus status;
}
