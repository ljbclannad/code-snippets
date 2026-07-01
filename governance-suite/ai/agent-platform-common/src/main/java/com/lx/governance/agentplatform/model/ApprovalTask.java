package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一待审批任务。
 * <p>
 * 表示一次需要人工介入的挂起事项。
 */
@Value
@Builder(toBuilder = true)
public class ApprovalTask {

    /** 审批任务标识。 */
    String approvalTaskId;

    /** 关联 run。 */
    String runId;

    /** 任务键。 */
    String taskKey;

    /** 任务名称。 */
    String taskName;

    /** 触发审批的原因。 */
    String reason;

    /** 指派处理人。 */
    String assigneeId;

    /** 当前审批状态。 */
    ApprovalStatus status;

    /** 创建时间。 */
    Long createdAt;

    /** 过期时间。 */
    Long expireAt;
}
