package com.lx.governance.agentplatform.model;

/**
 * 工作流节点类型。
 */
public enum WorkflowNodeType {

    ROUTE,
    AGENT_CALL,
    TOOL_CALL,
    BRANCH,
    PARALLEL,
    WAIT_APPROVAL,
    FINALIZE
}
