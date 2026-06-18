package com.lx.governance.agentplatform.model;

/**
 * 工作流边流转类型。
 */
public enum WorkflowTransitionType {

    ALWAYS,
    ON_SUCCESS,
    ON_FAILURE,
    ON_TIMEOUT,
    ON_FALLBACK,
    ON_APPROVED,
    ON_REJECTED,
    BY_EXPRESSION
}
