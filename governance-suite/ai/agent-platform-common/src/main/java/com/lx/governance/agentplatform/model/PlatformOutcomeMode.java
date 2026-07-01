package com.lx.governance.agentplatform.model;

/**
 * 平台任务最终如何回写到入口链路。
 */
public enum PlatformOutcomeMode {
    /** 直接回写直聊结果。 */
    DIRECT_CHAT_RESULT,
    /** 回写 agent 结果。 */
    AGENT_RESULT,
    /** 回退到直聊。 */
    DIRECT_CHAT_FALLBACK,
    /** 仍在审批中。 */
    PENDING_APPROVAL,
    /** 终止错误。 */
    TERMINAL_ERROR
}
