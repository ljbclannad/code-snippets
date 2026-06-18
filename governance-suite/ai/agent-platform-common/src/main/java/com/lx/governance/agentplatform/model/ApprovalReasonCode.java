package com.lx.governance.agentplatform.model;

/**
 * 平台统一审批原因枚举。
 */
public enum ApprovalReasonCode {
    HIGH_RISK_TOOL_CALL,
    MEDICAL_SAFETY_REVIEW,
    PRESCRIPTION_CHANGE_REVIEW,
    DIAGNOSIS_SENSITIVE_REVIEW,
    PATIENT_TASK_HUMAN_REVIEW,
    MANUAL_ESCALATION,
    POLICY_GUARDRAIL
}
