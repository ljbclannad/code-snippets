package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台 run 的单次状态转移描述。
 */
@Value
@Builder(toBuilder = true)
public class RunTransition {

    String runId;

    RunStatus fromStatus;

    RunStatus toStatus;

    RunTransitionTrigger trigger;

    RunEventType eventType;

    String reason;
}
