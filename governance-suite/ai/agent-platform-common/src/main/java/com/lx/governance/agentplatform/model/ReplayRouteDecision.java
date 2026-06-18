package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 回放视角下的路由决策摘要。
 * <p>
 * 用于解释一次 run 是直聊、升级还是回退。
 */
@Value
@Builder(toBuilder = true)
public class ReplayRouteDecision {

    /** 入口模式。 */
    PlatformEntryMode entryMode;

    /** 结果模式。 */
    PlatformOutcomeMode outcomeMode;

    /** 触发来源。 */
    String triggerSource;

    /** 回退原因。 */
    String fallbackReason;
}
