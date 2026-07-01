package com.lx.governance.agentplatform.model;

/**
 * 平台运行状态。
 */
public enum RunStatus {
    /** 待开始。 */
    PENDING,
    /** 执行中。 */
    RUNNING,
    /** 等待审批。 */
    WAITING_APPROVAL,
    /** 已完成。 */
    COMPLETED,
    /** 已失败。 */
    FAILED,
    /** 已降级。 */
    DEGRADED,
    /** 已取消。 */
    CANCELLED
}
