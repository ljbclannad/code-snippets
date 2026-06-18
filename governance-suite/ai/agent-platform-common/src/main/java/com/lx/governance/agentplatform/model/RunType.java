package com.lx.governance.agentplatform.model;

/**
 * 平台运行类型。
 */
public enum RunType {
    /** 对话 run。 */
    CHAT,
    /** 任务 run。 */
    TASK,
    /** 工作流 run。 */
    WORKFLOW,
    /** 审批恢复 run。 */
    APPROVAL_RESUME
}
