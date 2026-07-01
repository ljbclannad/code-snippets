package com.lx.governance.agentplatform.model;

/**
 * Prompt 模板类型。
 */
public enum PromptTemplateType {
    /** 系统提示词。 */
    SYSTEM,
    /** 子 agent 系统提示词。 */
    SUB_AGENT_SYSTEM,
    /** 用户提示词。 */
    USER,
    /** 协议修复提示词。 */
    PROTOCOL_REPAIR,
    /** 上下文片段模板。 */
    CONTEXT_SECTION
}
