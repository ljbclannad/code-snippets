package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一审批决策。
 * <p>
 * 记录人工对挂起任务的最终处理结果。
 */
@Value
@Builder(toBuilder = true)
public class ApprovalDecision {

    /** 审批任务标识。 */
    String approvalTaskId;

    /** 关联 run。 */
    String runId;

    /** 决策类型。 */
    ApprovalDecisionType decisionType;

    /** 操作人标识。 */
    String operatorId;

    /** 审批意见。 */
    String comment;

    /** 决策时间。 */
    Long decidedAt;
}
