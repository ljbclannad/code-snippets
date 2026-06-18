package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 回放视角下的路由决策摘要。
 */
@Value
@Builder(toBuilder = true)
public class ReplayRouteDecision {

    PlatformEntryMode entryMode;

    PlatformOutcomeMode outcomeMode;

    String triggerSource;

    String fallbackReason;
}
