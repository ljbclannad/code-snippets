package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 节点降级策略。
 * <p>
 * 当主路径失败时，定义应该切到哪个节点和什么状态。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowFallbackPolicy {

    /** 降级目标节点。 */
    String fallbackNodeId;

    /** 降级目标 agent。 */
    String fallbackAgentId;

    /** 降级原因。 */
    String fallbackReason;

    /** 降级后的 run 状态。 */
    RunStatus fallbackRunStatus;
}
