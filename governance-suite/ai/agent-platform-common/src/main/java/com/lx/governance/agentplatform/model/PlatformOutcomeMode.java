package com.lx.governance.agentplatform.model;

/**
 * 平台任务最终如何回写到入口链路。
 */
public enum PlatformOutcomeMode {
    DIRECT_CHAT_RESULT,
    AGENT_RESULT,
    DIRECT_CHAT_FALLBACK,
    PENDING_APPROVAL,
    TERMINAL_ERROR
}
