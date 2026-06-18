package com.lx.governance.agentplatform.model;

/**
 * 平台上下文传输通道。
 */
public enum PlatformContextChannel {
    /** 作为 agent 输入。 */
    AGENT_INPUT,
    /** agent 间交接。 */
    AGENT_HANDOFF,
    /** 服务间传输。 */
    SERVICE_TRANSPORT,
    /** 审计导出。 */
    AUDIT_EXPORT
}
