package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 节点或流程级超时策略。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowTimeoutPolicy {

    Long timeoutMs;

    String timeoutTargetNodeId;

    RunStatus timeoutRunStatus;
}
