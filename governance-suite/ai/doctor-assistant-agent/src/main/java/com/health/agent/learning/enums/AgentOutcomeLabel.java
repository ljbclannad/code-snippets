package com.health.agent.learning.enums;

/**
 * Agent 运行结果标签。
 */
public enum AgentOutcomeLabel {
    SUCCESS,
    SUCCESS_WITH_FALLBACK,
    MANUAL_REVIEW_REQUIRED,
    FAILED_TIMEOUT,
    FAILED_TOOL_ERROR,
    FAILED_PROTOCOL_ERROR,
    FAILED_SAFETY_BLOCK,
    FAILED_RUNTIME
}
