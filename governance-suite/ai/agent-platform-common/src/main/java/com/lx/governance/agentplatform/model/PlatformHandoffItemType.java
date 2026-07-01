package com.lx.governance.agentplatform.model;

/**
 * Agent 之间交接的中间产物类型。
 */
public enum PlatformHandoffItemType {
    /** 上下文槽位。 */
    CONTEXT_SLOT,
    /** 证据。 */
    EVIDENCE,
    /** 工具输出。 */
    TOOL_OUTPUT,
    /** 汇总摘要。 */
    SUMMARY
}
