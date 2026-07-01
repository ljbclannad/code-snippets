package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一审批请求。
 * <p>
 * 这是创建审批任务时对外暴露的完整请求模型。
 */
@Value
@Builder(toBuilder = true)
public class ApprovalRequest {

    /** 审批任务标识。 */
    String approvalTaskId;

    /** 关联 run。 */
    String runId;

    /** 所属工作流。 */
    String workflowId;

    /** 审批标题。 */
    String title;

    /** 审批描述。 */
    String description;

    /** 原因码。 */
    ApprovalReasonCode reasonCode;

    /** 原因详情。 */
    String reasonDetail;

    /** 指派处理人。 */
    String assigneeId;

    /** 当前状态。 */
    ApprovalStatus status;

    /** 扩展载荷。 */
    Map<String, Object> payload;

    /** SLA 策略。 */
    ApprovalSlaPolicy slaPolicy;

    /** 创建时间。 */
    Long createdAt;

    /** 过期时间。 */
    Long expireAt;
}
