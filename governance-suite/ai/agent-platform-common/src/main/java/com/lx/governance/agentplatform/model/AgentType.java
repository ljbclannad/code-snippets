package com.lx.governance.agentplatform.model;

/**
 * 平台侧 Agent 分类。
 */
public enum AgentType {
    /** 入口型 agent。 */
    ENTRYPOINT,
    /** 领域型 agent。 */
    DOMAIN,
    /** 子 agent。 */
    SUB_AGENT,
    /** 工具代理。 */
    TOOL_PROXY,
    /** 审核型 agent。 */
    REVIEW
}
