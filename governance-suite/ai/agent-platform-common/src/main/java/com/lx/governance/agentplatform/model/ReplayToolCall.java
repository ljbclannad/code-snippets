package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 回放视角下的工具调用摘要。
 */
@Value
@Builder(toBuilder = true)
public class ReplayToolCall {

    String toolCallId;

    String runId;

    String stepId;

    String toolName;

    String toolDescription;

    String status;

    String reason;

    String resultSummary;

    Long createdAt;

    Long expireAt;
}
