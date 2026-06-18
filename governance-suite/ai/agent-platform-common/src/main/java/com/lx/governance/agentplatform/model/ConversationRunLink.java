package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 对话入口与平台 run 的关联关系。
 */
@Value
@Builder(toBuilder = true)
public class ConversationRunLink {

    String conversationId;

    String sessionId;

    String runId;

    RunType runType;

    PlatformEntryMode entryMode;

    PlatformOutcomeMode outcomeMode;

    String triggerSource;

    String fallbackReason;

    Long linkedAt;
}
