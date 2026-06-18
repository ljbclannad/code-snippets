package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一循环执行上下文。
 * <p>
 * 用于把循环目标、起止步数和状态一起交给模板执行器。
 */
@Value
@Builder(toBuilder = true)
public class LoopContext<T> {

    /** run 标识。 */
    String runId;

    /** 工作流标识。 */
    String workflowId;

    /** 循环目标。 */
    String goal;

    /** 起始步骤下标。 */
    Integer startStep;

    /** 最大步骤数。 */
    Integer maxSteps;

    /** 循环开始时间。 */
    Long startedAt;

    /** 循环中的业务状态。 */
    T state;
}
