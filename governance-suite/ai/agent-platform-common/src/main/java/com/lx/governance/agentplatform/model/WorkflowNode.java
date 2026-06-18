package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 工作流节点定义。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowNode {

    String nodeId;

    String nodeName;

    WorkflowNodeType nodeType;

    String agentId;

    String toolId;

    @Singular("configEntry")
    Map<String, Object> config;

    @Singular("inputMappingEntry")
    Map<String, Object> inputMapping;

    @Singular("outputMappingEntry")
    Map<String, Object> outputMapping;

    WorkflowTimeoutPolicy timeoutPolicy;

    WorkflowRetryPolicy retryPolicy;

    WorkflowFallbackPolicy fallbackPolicy;

    WorkflowApprovalPolicy approvalPolicy;

    WorkflowManualInterventionPolicy manualInterventionPolicy;
}
