package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一挂起执行快照。
 */
@Value
@Builder(toBuilder = true)
public class SuspendedExecution {

    String runId;

    String workflowId;

    String goal;

    String suspendedNodeType;

    RunStatus status;

    ApprovalTask approvalTask;

    Long suspendedAt;
}
