package com.lx.governance.aichat.dto;

/**
 * 患者对话升级到 Agent 后的首批任务类型。
 */
public enum TaskType {
    /**
     * 不升级到 Agent，继续普通对话。
     */
    NONE,

    /**
     * 患者随访分析。
     */
    PATIENT_FOLLOW_UP_ANALYSIS,

    /**
     * 风险分层与转人工建议。
     */
    RISK_STRATIFICATION_AND_ESCALATION,

    /**
     * 下一步随访动作建议。
     */
    NEXT_FOLLOW_UP_ACTION_RECOMMENDATION
}
