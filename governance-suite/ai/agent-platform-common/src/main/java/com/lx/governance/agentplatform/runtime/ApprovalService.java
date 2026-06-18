package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.SuspendedExecution;

/**
 * 平台统一审批服务抽象。
 */
public interface ApprovalService<T, R> {

    SuspendedExecution describeSuspendedExecution(String workflowId, String goal, T approvalRecord);

    R loadApprovedResumeState(String runId, String approvalTaskId);
}
