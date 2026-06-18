package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 审批 SLA / 超时策略。
 */
@Value
@Builder(toBuilder = true)
public class ApprovalSlaPolicy {

    Integer dueInMinutes;

    Integer expireInMinutes;

    ApprovalDecisionType timeoutDecisionType;

    ApprovalReminderPolicy reminderPolicy;
}
