package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台治理层对入口流量的策略判定。
 */
@Value
@Builder(toBuilder = true)
public class PlatformDispatchDecision {

    boolean allowed;

    String reason;
}
