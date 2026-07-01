package com.lx.governance.agentplatform.runtime;

/**
 * 审批通过后的恢复执行器。
 * <p>
 * 负责把审批结果还原成可继续执行的运行态。
 */
public interface ApprovalResumeExecutor<R> {

    R loadApprovedResumeState(String runId, String approvalTaskId);
}
