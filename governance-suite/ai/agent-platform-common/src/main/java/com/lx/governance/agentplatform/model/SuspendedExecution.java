package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一挂起执行快照。
 * <p>
 * 用于在审批或人工介入时冻结当前工作流状态。
 */
@Value
@Builder(toBuilder = true)
public class SuspendedExecution {

    /** run 标识。 */
    String runId;

    /** 工作流标识。 */
    String workflowId;

    /** 当前目标。 */
    String goal;

    /** 被挂起的节点类型。 */
    String suspendedNodeType;

    /** 挂起时 run 状态。 */
    RunStatus status;

    /** 触发挂起的审批任务。 */
    ApprovalTask approvalTask;

    /** 挂起时间。 */
    Long suspendedAt;
}
