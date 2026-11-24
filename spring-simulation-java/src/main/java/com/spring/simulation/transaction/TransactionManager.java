package com.spring.simulation.transaction;

import java.util.ArrayDeque;
import java.util.Deque;

/** 教学用状态模型：逻辑调用共享 owner，不绑定 JDBC 资源或真实保存点。 */
public class TransactionManager {
    private final ThreadLocal<Deque<TransactionStatus>> calls = ThreadLocal.withInitial(ArrayDeque::new);

    public TransactionStatus begin(Propagation propagation) {
        TransactionStatus current = getCurrentTransaction();
        if (propagation == Propagation.MANDATORY && current == null)
            throw new IllegalStateException("No existing transaction found (MANDATORY)");
        if (propagation == Propagation.NEVER && current != null)
            throw new IllegalStateException("Existing transaction found (NEVER)");
        TransactionStatus status = new TransactionStatus();
        switch (propagation) {
            case NOT_SUPPORTED:
                status.suspendedTransaction = current;
                break;
            case NEVER:
                break;
            case SUPPORTS:
            case MANDATORY:
                status.owner = current;
                break;
            case REQUIRES_NEW:
                status.suspendedTransaction = current;
                status.owner = status;
                status.newTransaction = true;
                break;
            case NESTED:
                status.owner = status;
                status.nested = current != null;
                status.parent = current;
                status.newTransaction = current == null;
                break;
            case REQUIRED:
                status.owner = current == null ? status : current;
                status.newTransaction = current == null;
                break;
            default: throw new IllegalArgumentException("Unknown propagation: " + propagation);
        }
        if (status.owner == status) status.active = true;
        if (status.suspendedTransaction != null) status.suspendedTransaction.suspended = true;
        calls.get().push(status);
        return status;
    }
    public void commit(TransactionStatus status) { complete(status, false); }
    public void rollback(TransactionStatus status) { complete(status, true); }

    /** 供资源管理器在底层资源失败时阻止相关事务提交。 */
    protected void markRollbackOnly(TransactionStatus status) {
        if (status != null && status.owner != null) status.owner.rollbackOnly = true;
    }

    private void complete(TransactionStatus status, boolean rollback) {
        if (status == null) return;
        Deque<TransactionStatus> stack = calls.get();
        if (status.completed || stack.peek() != status)
            throw new IllegalStateException("Transaction calls must complete once in reverse begin order");
        boolean unexpectedRollback = !rollback && status.owner == status && status.rollbackOnly;
        try {
            if (status.owner == status) status.active = false;
            else if (rollback && status.owner != null) status.owner.rollbackOnly = true;
            status.completed = true;
        } finally {
            stack.pop();
            if (status.suspendedTransaction != null) status.suspendedTransaction.suspended = false;
            if (stack.isEmpty()) calls.remove();
        }
        if (unexpectedRollback) throw new IllegalStateException("Transaction rolled back because it was marked rollback-only");
    }
    public TransactionStatus getCurrentTransaction() {
        Deque<TransactionStatus> stack = calls.get();
        if (stack.isEmpty()) { calls.remove(); return null; }
        TransactionStatus owner = stack.peek().owner;
        return owner != null && owner.active && !owner.suspended ? owner : null;
    }
    public static class TransactionStatus {
        private TransactionStatus owner, suspendedTransaction, parent;
        private boolean active, newTransaction, nested, suspended, rollbackOnly, completed;
        public boolean isActive() { return !completed && owner != null && owner.active && !owner.suspended; }
        public boolean isNewTransaction() { return newTransaction; }
        public boolean isNested() { return nested; }
        public boolean isSuspended() { return owner != null && owner.suspended; }
        public TransactionStatus getParent() { return parent; }
        public boolean isCompleted() { return completed; }
        public boolean isRollbackOnly() { return owner != null && owner.rollbackOnly; }
    }
}
