package com.lx.governance.agentplatform.model;

/**
 * 审计输出作用域，用于隔离患者可见与内部输出。
 */
public enum PlatformAuditOutputScope {
    /** 患者可见。 */
    PATIENT_VISIBLE,
    /** 仅内部可见。 */
    INTERNAL_ONLY
}
