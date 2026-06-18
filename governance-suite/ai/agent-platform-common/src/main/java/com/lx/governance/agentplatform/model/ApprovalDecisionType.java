package com.lx.governance.agentplatform.model;

/**
 * 平台统一审批决策类型。
 */
public enum ApprovalDecisionType {
    /** 审批通过。 */
    APPROVED,
    /** 审批拒绝。 */
    REJECTED,
    /** 审批超时。 */
    EXPIRED
}
