package com.lx.governance.agentplatform.model;

/**
 * 平台级工具幂等策略。
 */
public enum ToolIdempotencyPolicy {
    /** 幂等。 */
    IDEMPOTENT,
    /** 非幂等。 */
    NON_IDEMPOTENT,
    /** 条件幂等。 */
    CONDITIONAL
}
