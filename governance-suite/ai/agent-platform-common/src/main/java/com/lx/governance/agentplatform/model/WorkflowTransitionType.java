package com.lx.governance.agentplatform.model;

/**
 * 工作流边流转类型。
 */
public enum WorkflowTransitionType {

    /** 无条件流转。 */
    ALWAYS,
    /** 成功后流转。 */
    ON_SUCCESS,
    /** 失败后流转。 */
    ON_FAILURE,
    /** 超时后流转。 */
    ON_TIMEOUT,
    /** 降级后流转。 */
    ON_FALLBACK,
    /** 审批通过后流转。 */
    ON_APPROVED,
    /** 审批拒绝后流转。 */
    ON_REJECTED,
    /** 表达式匹配后流转。 */
    BY_EXPRESSION
}
