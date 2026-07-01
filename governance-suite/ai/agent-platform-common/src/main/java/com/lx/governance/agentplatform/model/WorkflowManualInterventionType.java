package com.lx.governance.agentplatform.model;

/**
 * 人工介入方式。
 */
public enum WorkflowManualInterventionType {

    /** 不需要人工介入。 */
    NONE,
    /** 走审批。 */
    APPROVAL,
    /** 走人工复核。 */
    REVIEW,
    /** 直接接管。 */
    TAKEOVER
}
