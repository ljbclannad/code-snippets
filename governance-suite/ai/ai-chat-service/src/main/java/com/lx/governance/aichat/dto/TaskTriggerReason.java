package com.lx.governance.aichat.dto;

/**
 * TaskTriggerRouter 的规则命中原因。
 */
public enum TaskTriggerReason {
    PROFILE_COMPLETION_FOLLOW_UP,
    CASUAL_CHAT_SIGNAL,
    SIMPLE_FACT_QUERY,
    EXPLICIT_ANALYSIS_REQUEST,
    TREND_ANALYSIS_REQUEST,
    HUMAN_REVIEW_SIGNAL,
    NEXT_ACTION_REQUEST,
    COMPLEX_CONTEXT_SIGNAL,
    DEFAULT_DIRECT_CHAT
}
