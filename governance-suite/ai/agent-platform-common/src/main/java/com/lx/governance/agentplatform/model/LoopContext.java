package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一循环执行上下文。
 */
@Value
@Builder(toBuilder = true)
public class LoopContext<T> {

    String runId;

    String workflowId;

    String goal;

    Integer startStep;

    Integer maxSteps;

    Long startedAt;

    T state;
}
