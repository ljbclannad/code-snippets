package com.lx.governance.agentplatform.model;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台统一工作流定义。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowDefinition {

    String workflowId;

    String workflowName;

    String version;

    String description;

    String startNodeId;

    @Singular("inputSchemaEntry")
    Map<String, Object> inputSchema;

    @Singular("outputSchemaEntry")
    Map<String, Object> outputSchema;

    WorkflowTimeoutPolicy timeoutPolicy;

    WorkflowRetryPolicy retryPolicy;

    WorkflowFallbackPolicy fallbackPolicy;

    WorkflowManualInterventionPolicy manualInterventionPolicy;

    @Singular("node")
    List<WorkflowNode> nodes;

    @Singular("edge")
    List<WorkflowEdge> edges;
}
