package com.lx.governance.agentplatform.model;

/**
 * 平台运行事件类型。
 */
public enum RunEventType {
    RUN_CREATED,
    RUN_STARTED,
    STEP_STARTED,
    STEP_COMPLETED,
    STEP_FAILED,
    WAITING_APPROVAL,
    APPROVAL_RESUMED,
    FALLBACK_TRIGGERED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_DEGRADED,
    RUN_CANCELLED
}
