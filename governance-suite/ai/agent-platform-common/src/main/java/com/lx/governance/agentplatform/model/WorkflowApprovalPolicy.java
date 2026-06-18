package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 审批策略。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowApprovalPolicy {

    String approvalScope;

    String approvalKey;

    String approvalReason;

    String approvedTargetNodeId;

    String rejectedTargetNodeId;

    Long approvalTimeoutMs;
}
