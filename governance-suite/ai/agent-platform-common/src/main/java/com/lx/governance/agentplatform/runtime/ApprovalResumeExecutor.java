package com.lx.governance.agentplatform.runtime;

/**
 * 审批通过后的恢复执行器。
 */
public interface ApprovalResumeExecutor<R> {

    R loadApprovedResumeState(String runId, String approvalTaskId);
}
