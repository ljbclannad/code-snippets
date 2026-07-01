package com.lx.governance.agentplatform.runtime;

/**
 * 平台统一审批任务仓储抽象。
 */
public interface ApprovalRepository<T> {

    T save(T approvalRecord);

    T findById(String approvalTaskId);
}
