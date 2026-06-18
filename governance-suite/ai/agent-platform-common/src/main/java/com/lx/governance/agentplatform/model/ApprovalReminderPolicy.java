package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 审批提醒策略。
 */
@Value
@Builder(toBuilder = true)
public class ApprovalReminderPolicy {

    Integer firstReminderAfterMinutes;

    Integer repeatReminderEveryMinutes;

    Integer maxReminderCount;
}
