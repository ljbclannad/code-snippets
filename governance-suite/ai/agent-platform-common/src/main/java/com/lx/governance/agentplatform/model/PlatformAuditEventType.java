package com.lx.governance.agentplatform.model;

/**
 * 平台统一审计事件类型。
 */
public enum PlatformAuditEventType {
    /** run 已持久化。 */
    RUN_PERSISTED,
    /** 审批状态已同步。 */
    APPROVAL_STATUS_SYNCED
}
