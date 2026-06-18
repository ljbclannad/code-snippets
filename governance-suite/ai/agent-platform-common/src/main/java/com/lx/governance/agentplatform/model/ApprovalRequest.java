package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一审批请求。
 */
@Value
@Builder(toBuilder = true)
public class ApprovalRequest {

    String approvalTaskId;

    String runId;

    String workflowId;

    String title;

    String description;

    ApprovalReasonCode reasonCode;

    String reasonDetail;

    String assigneeId;

    ApprovalStatus status;

    Map<String, Object> payload;

    ApprovalSlaPolicy slaPolicy;

    Long createdAt;

    Long expireAt;
}
