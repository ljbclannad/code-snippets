package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一审批决策。
 */
@Value
@Builder(toBuilder = true)
public class ApprovalDecision {

    String approvalTaskId;

    String runId;

    ApprovalDecisionType decisionType;

    String operatorId;

    String comment;

    Long decidedAt;
}
