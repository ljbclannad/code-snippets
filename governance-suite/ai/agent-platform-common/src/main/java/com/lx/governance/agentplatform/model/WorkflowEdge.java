package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 工作流节点连线定义。
 * <p>
 * 用条件表达式描述节点之间的跳转关系。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowEdge {

    /** 边标识。 */
    String edgeId;

    /** 源节点。 */
    String sourceNodeId;

    /** 目标节点。 */
    String targetNodeId;

    /** 流转类型。 */
    WorkflowTransitionType transitionType;

    /** 条件表达式。 */
    String conditionExpression;
}
