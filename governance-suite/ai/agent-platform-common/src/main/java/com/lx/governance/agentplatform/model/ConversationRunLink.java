package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 对话入口与平台 run 的关联关系。
 * <p>
 * 回放和审计会依赖这条映射恢复入口模式与降级原因。
 */
@Value
@Builder(toBuilder = true)
public class ConversationRunLink {

    /** 对话标识。 */
    String conversationId;

    /** 会话标识。 */
    String sessionId;

    /** run 标识。 */
    String runId;

    /** run 类型。 */
    RunType runType;

    /** 入口模式。 */
    PlatformEntryMode entryMode;

    /** 结果模式。 */
    PlatformOutcomeMode outcomeMode;

    /** 触发来源。 */
    String triggerSource;

    /** 回退原因。 */
    String fallbackReason;

    /** 关联时间。 */
    Long linkedAt;
}
