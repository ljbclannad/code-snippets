package com.lx.governance.agentplatform.model;

/**
 * 平台级工具幂等策略。
 */
public enum ToolIdempotencyPolicy {
    IDEMPOTENT,
    NON_IDEMPOTENT,
    CONDITIONAL
}
