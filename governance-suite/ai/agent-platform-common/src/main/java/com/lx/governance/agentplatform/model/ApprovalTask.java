package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一待审批任务。
 */
@Value
@Builder(toBuilder = true)
public class ApprovalTask {

    String approvalTaskId;

    String runId;

    String taskKey;

    String taskName;

    String reason;

    String assigneeId;

    ApprovalStatus status;

    Long createdAt;

    Long expireAt;
}
