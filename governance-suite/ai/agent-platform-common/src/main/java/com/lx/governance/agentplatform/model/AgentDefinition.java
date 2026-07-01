package com.lx.governance.agentplatform.model;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台统一 Agent 定义。
 * <p>
 * 描述 agent 的能力、可见范围、版本和输入输出契约。
 */
@Value
@Builder(toBuilder = true)
public class AgentDefinition {

    /** Agent 标识。 */
    String agentId;

    /** 展示名称。 */
    String displayName;

    /** Agent 类型。 */
    AgentType agentType;

    /** 能力标签。 */
    @Singular("capability")
    List<String> capabilities;

    /** 支持的任务类型。 */
    @Singular("supportedTaskType")
    List<String> supportedTaskTypes;

    /** 输入结构。 */
    Map<String, Object> inputSchema;

    /** 输出结构。 */
    Map<String, Object> outputSchema;

    /** 允许调用的工具。 */
    @Singular("allowedTool")
    List<String> allowedTools;

    /** 风险等级。 */
    String riskLevel;

    /** 所有者。 */
    String owner;

    /** 版本。 */
    String version;

    /** 可见灰度分组。 */
    @Singular("visibleGroupId")
    List<String> visibleGroupIds;

    /** 可见用户。 */
    @Singular("visibleUserId")
    List<String> visibleUserIds;

    /** 可见租户。 */
    @Singular("visibleTenantId")
    List<String> visibleTenantIds;

    /** 当前状态。 */
    AgentStatus status;
}
