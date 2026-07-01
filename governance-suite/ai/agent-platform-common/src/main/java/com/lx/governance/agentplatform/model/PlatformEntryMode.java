package com.lx.governance.agentplatform.model;

/**
 * 入口请求进入平台的方式。
 */
public enum PlatformEntryMode {
    /** 仅直聊。 */
    DIRECT_CHAT_ONLY,
    /** 直聊但也创建 run。 */
    DIRECT_CHAT_WITH_RUN,
    /** 直接进入 agent run。 */
    AGENT_RUN
}
