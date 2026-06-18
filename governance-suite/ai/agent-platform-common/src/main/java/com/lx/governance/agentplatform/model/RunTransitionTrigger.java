package com.lx.governance.agentplatform.model;

/**
 * 标准运行状态转移触发器。
 */
public enum RunTransitionTrigger {
    /** 创建。 */
    CREATE,
    /** 启动。 */
    START,
    /** 进入审批等待。 */
    WAIT_FOR_APPROVAL,
    /** 审批通过后恢复。 */
    APPROVAL_APPROVED_RESUME,
    /** 审批拒绝后恢复。 */
    APPROVAL_REJECTED_RESUME,
    /** 重试。 */
    RETRY,
    /** 完成。 */
    COMPLETE,
    /** 失败。 */
    FAIL,
    /** 降级。 */
    DEGRADE,
    /** 回退到直聊。 */
    FALLBACK_TO_DIRECT_CHAT,
    /** 取消。 */
    CANCEL
}
