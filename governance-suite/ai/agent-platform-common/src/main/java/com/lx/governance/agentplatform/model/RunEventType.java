package com.lx.governance.agentplatform.model;

/**
 * 平台运行事件类型。
 */
public enum RunEventType {
    /** run 创建。 */
    RUN_CREATED,
    /** run 启动。 */
    RUN_STARTED,
    /** 步骤启动。 */
    STEP_STARTED,
    /** 步骤完成。 */
    STEP_COMPLETED,
    /** 步骤失败。 */
    STEP_FAILED,
    /** 等待审批。 */
    WAITING_APPROVAL,
    /** 审批恢复。 */
    APPROVAL_RESUMED,
    /** 触发回退。 */
    FALLBACK_TRIGGERED,
    /** run 完成。 */
    RUN_COMPLETED,
    /** run 失败。 */
    RUN_FAILED,
    /** run 降级。 */
    RUN_DEGRADED,
    /** run 取消。 */
    RUN_CANCELLED
}
