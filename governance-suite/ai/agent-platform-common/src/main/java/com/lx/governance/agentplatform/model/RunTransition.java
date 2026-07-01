package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台 run 的单次状态转移描述。
 * <p>
 * 这里同时保留了触发器和落地后的事件类型，便于审计和回放。
 */
@Value
@Builder(toBuilder = true)
public class RunTransition {

    /** run 标识。 */
    String runId;

    /** 转移前状态。 */
    RunStatus fromStatus;

    /** 转移后状态。 */
    RunStatus toStatus;

    /** 触发器。 */
    RunTransitionTrigger trigger;

    /** 对应的运行事件类型。 */
    RunEventType eventType;

    /** 转移动因。 */
    String reason;
}
