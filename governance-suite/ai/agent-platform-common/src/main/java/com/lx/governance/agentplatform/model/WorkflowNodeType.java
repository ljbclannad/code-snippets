package com.lx.governance.agentplatform.model;

/**
 * 工作流节点类型。
 */
public enum WorkflowNodeType {

    /** 路由节点。 */
    ROUTE,
    /** Agent 调用节点。 */
    AGENT_CALL,
    /** Tool 调用节点。 */
    TOOL_CALL,
    /** 分支节点。 */
    BRANCH,
    /** 并行节点。 */
    PARALLEL,
    /** 等待审批节点。 */
    WAIT_APPROVAL,
    /** 收尾节点。 */
    FINALIZE
}
