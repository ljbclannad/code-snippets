package com.lx.governance.agentplatform.model;

/**
 * 平台运行状态。
 */
public enum RunStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    DEGRADED,
    CANCELLED
}
