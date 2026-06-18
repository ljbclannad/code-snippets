package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 节点降级策略。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowFallbackPolicy {

    String fallbackNodeId;

    String fallbackAgentId;

    String fallbackReason;

    RunStatus fallbackRunStatus;
}
