package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台治理层对入口流量的策略判定。
 * <p>
 * `allowed=false` 时通常会回退到直聊或终止错误路径。
 */
@Value
@Builder(toBuilder = true)
public class PlatformDispatchDecision {

    /** 是否允许进入 agent 链路。 */
    boolean allowed;

    /** 命中策略的原因。 */
    String reason;
}
