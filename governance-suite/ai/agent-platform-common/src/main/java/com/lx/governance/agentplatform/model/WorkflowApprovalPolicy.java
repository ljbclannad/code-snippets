package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 审批策略。
 * <p>
 * 指定在哪个范围内触发审批、批准或拒绝后分别跳转到哪里。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowApprovalPolicy {

    /** 审批作用域。 */
    String approvalScope;

    /** 审批键。 */
    String approvalKey;

    /** 审批原因。 */
    String approvalReason;

    /** 审批通过后的目标节点。 */
    String approvedTargetNodeId;

    /** 审批拒绝后的目标节点。 */
    String rejectedTargetNodeId;

    /** 审批超时毫秒数。 */
    Long approvalTimeoutMs;
}
