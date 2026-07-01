package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一恢复执行请求。
 */
@Value
@Builder(toBuilder = true)
public class ResumeRunRequest {

    String runId;

    String approvalTaskId;

    ApprovalDecisionType decisionType;

    String operatorId;

    Long requestedAt;
}
