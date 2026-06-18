package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 审批 SLA / 超时策略。
 * <p>
 * 控制审批多久到期、何时过期以及是否发送提醒。
 */
@Value
@Builder(toBuilder = true)
public class ApprovalSlaPolicy {

    /** 距离创建后的到期分钟数。 */
    Integer dueInMinutes;

    /** 距离创建后的失效分钟数。 */
    Integer expireInMinutes;

    /** 超时时的默认决策。 */
    ApprovalDecisionType timeoutDecisionType;

    /** 提醒策略。 */
    ApprovalReminderPolicy reminderPolicy;
}
