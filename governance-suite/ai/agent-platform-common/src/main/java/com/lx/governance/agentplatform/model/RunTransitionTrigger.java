package com.lx.governance.agentplatform.model;

/**
 * 标准运行状态转移触发器。
 */
public enum RunTransitionTrigger {
    CREATE,
    START,
    WAIT_FOR_APPROVAL,
    APPROVAL_APPROVED_RESUME,
    APPROVAL_REJECTED_RESUME,
    RETRY,
    COMPLETE,
    FAIL,
    DEGRADE,
    FALLBACK_TO_DIRECT_CHAT,
    CANCEL
}
