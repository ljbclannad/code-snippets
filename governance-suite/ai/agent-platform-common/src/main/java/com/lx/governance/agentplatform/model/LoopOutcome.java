package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一循环执行结果。
 * <p>
 * 这是模板执行器返回给上层的最终收敛结果。
 */
@Value
@Builder(toBuilder = true)
public class LoopOutcome<S, R> {

    /** run 标识。 */
    String runId;

    /** 终止原因。 */
    LoopTerminationReason terminationReason;

    /** 最终 run 状态。 */
    RunStatus runStatus;

    /** 已完成步骤数。 */
    Integer completedSteps;

    /** 循环最终产出。 */
    R result;

    /** 收敛后的状态。 */
    S state;

    /** 失败原因。 */
    String errorMessage;

    /** 结束时间。 */
    Long endedAt;
}
