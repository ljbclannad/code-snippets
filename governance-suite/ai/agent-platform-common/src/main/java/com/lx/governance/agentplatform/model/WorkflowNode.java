package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 工作流节点定义。
 * <p>
 * 节点是工作流中的最小编排单元，可能是路由、agent、tool 或审批点。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowNode {

    /** 节点标识。 */
    String nodeId;

    /** 节点名称。 */
    String nodeName;

    /** 节点类型。 */
    WorkflowNodeType nodeType;

    /** 关联 agent。 */
    String agentId;

    /** 关联 tool。 */
    String toolId;

    /** 节点配置。 */
    @Singular("configEntry")
    Map<String, Object> config;

    /** 入参映射。 */
    @Singular("inputMappingEntry")
    Map<String, Object> inputMapping;

    /** 出参映射。 */
    @Singular("outputMappingEntry")
    Map<String, Object> outputMapping;

    /** 超时策略。 */
    WorkflowTimeoutPolicy timeoutPolicy;

    /** 重试策略。 */
    WorkflowRetryPolicy retryPolicy;

    /** 降级策略。 */
    WorkflowFallbackPolicy fallbackPolicy;

    /** 审批策略。 */
    WorkflowApprovalPolicy approvalPolicy;

    /** 人工介入策略。 */
    WorkflowManualInterventionPolicy manualInterventionPolicy;
}
