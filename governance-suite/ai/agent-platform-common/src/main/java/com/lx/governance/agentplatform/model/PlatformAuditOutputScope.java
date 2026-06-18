package com.lx.governance.agentplatform.model;

/**
 * 审计输出作用域，用于隔离患者可见与内部输出。
 */
public enum PlatformAuditOutputScope {
    PATIENT_VISIBLE,
    INTERNAL_ONLY
}
