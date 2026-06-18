package com.lx.governance.agentplatform.runtime;

/**
 * 平台统一路由目标。
 */
public enum PlatformRouteTarget {
    DIRECT_CHAT_FALLBACK,
    AGENT,
    PENDING_REVIEW_SHORT_CIRCUIT,
    TERMINAL_ERROR
}
