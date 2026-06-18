package com.lx.governance.agentplatform.model;

/**
 * 平台统一审批状态。
 */
public enum ApprovalStatus {

    /** 待审批。 */
    PENDING,
    /** 已批准。 */
    APPROVED,
    /** 已拒绝。 */
    REJECTED,
    /** 已过期。 */
    EXPIRED
}
