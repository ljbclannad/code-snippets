package com.lx.governance.agentplatform.model;

/**
 * 平台上下文域。
 */
public enum PlatformContextDomain {
    /** 对话上下文。 */
    CONVERSATION_CONTEXT,
    /** 用户画像快照。 */
    USER_PROFILE_SNAPSHOT,
    /** 分层记忆。 */
    LAYERED_MEMORIES,
    /** 任务触发判定。 */
    TASK_TRIGGER_DECISION,
    /** Agent 工作记忆。 */
    AGENT_WORKING_MEMORY,
    /** 证据。 */
    EVIDENCE,
    /** 工具输出。 */
    TOOL_OUTPUT
}
