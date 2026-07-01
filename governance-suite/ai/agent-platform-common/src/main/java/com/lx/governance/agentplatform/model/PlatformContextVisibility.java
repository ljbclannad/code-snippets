package com.lx.governance.agentplatform.model;

/**
 * 平台上下文可见性级别。
 */
public enum PlatformContextVisibility {
    /** 患者可见。 */
    USER_VISIBLE,
    /** 医生可见。 */
    DOCTOR_VISIBLE,
    /** Agent 内部可见。 */
    AGENT_INTERNAL,
    /** 仅审计可见。 */
    AUDIT_ONLY
}
