package com.lx.governance.agentplatform.model;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台统一工作流定义。
 * <p>
 * 描述一个工作流的拓扑、输入输出契约和全局治理策略。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowDefinition {

    /** 工作流标识。 */
    String workflowId;

    /** 工作流名称。 */
    String workflowName;

    /** 版本号。 */
    String version;

    /** 文字说明。 */
    String description;

    /** 起始节点。 */
    String startNodeId;

    /** 输入结构定义。 */
    @Singular("inputSchemaEntry")
    Map<String, Object> inputSchema;

    /** 输出结构定义。 */
    @Singular("outputSchemaEntry")
    Map<String, Object> outputSchema;

    /** 超时策略。 */
    WorkflowTimeoutPolicy timeoutPolicy;

    /** 重试策略。 */
    WorkflowRetryPolicy retryPolicy;

    /** 降级策略。 */
    WorkflowFallbackPolicy fallbackPolicy;

    /** 人工介入策略。 */
    WorkflowManualInterventionPolicy manualInterventionPolicy;

    /** 节点列表。 */
    @Singular("node")
    List<WorkflowNode> nodes;

    /** 边列表。 */
    @Singular("edge")
    List<WorkflowEdge> edges;
}
