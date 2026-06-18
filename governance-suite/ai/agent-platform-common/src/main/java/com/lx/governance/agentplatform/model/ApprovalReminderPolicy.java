package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 审批提醒策略。
 * <p>
 * 定义首次提醒、重复提醒和最大提醒次数。
 */
@Value
@Builder(toBuilder = true)
public class ApprovalReminderPolicy {

    /** 首次提醒延迟分钟数。 */
    Integer firstReminderAfterMinutes;

    /** 重复提醒间隔分钟数。 */
    Integer repeatReminderEveryMinutes;

    /** 最大提醒次数。 */
    Integer maxReminderCount;
}
