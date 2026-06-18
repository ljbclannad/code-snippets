package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.SuspendedExecution;

/**
 * 平台统一审批服务抽象。
 * <p>
 * 上层只关心挂起快照和恢复状态，具体审批存储由实现类负责。
 */
public interface ApprovalService<T, R> {

    SuspendedExecution describeSuspendedExecution(String workflowId, String goal, T approvalRecord);

    R loadApprovedResumeState(String runId, String approvalTaskId);
}
