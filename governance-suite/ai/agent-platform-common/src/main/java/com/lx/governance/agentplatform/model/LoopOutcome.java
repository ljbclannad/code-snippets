package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一循环执行结果。
 */
@Value
@Builder(toBuilder = true)
public class LoopOutcome<S, R> {

    String runId;

    LoopTerminationReason terminationReason;

    RunStatus runStatus;

    Integer completedSteps;

    R result;

    S state;

    String errorMessage;

    Long endedAt;
}
