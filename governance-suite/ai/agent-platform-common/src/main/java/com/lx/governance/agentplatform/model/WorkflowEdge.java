package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 工作流节点连线定义。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowEdge {

    String edgeId;

    String sourceNodeId;

    String targetNodeId;

    WorkflowTransitionType transitionType;

    String conditionExpression;
}
