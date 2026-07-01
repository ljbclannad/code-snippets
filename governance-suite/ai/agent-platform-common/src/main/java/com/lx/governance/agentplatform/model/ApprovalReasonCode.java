package com.lx.governance.agentplatform.model;

/**
 * 平台统一审批原因枚举。
 */
public enum ApprovalReasonCode {
    /** 高风险工具调用。 */
    HIGH_RISK_TOOL_CALL,
    /** 医疗安全复核。 */
    MEDICAL_SAFETY_REVIEW,
    /** 处方变更复核。 */
    PRESCRIPTION_CHANGE_REVIEW,
    /** 诊断敏感项复核。 */
    DIAGNOSIS_SENSITIVE_REVIEW,
    /** 患者任务人工复核。 */
    PATIENT_TASK_HUMAN_REVIEW,
    /** 人工升级。 */
    MANUAL_ESCALATION,
    /** 策略护栏触发。 */
    POLICY_GUARDRAIL
}
