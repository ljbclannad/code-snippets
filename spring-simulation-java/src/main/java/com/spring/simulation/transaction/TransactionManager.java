package com.spring.simulation.transaction;

import java.util.Stack;

/**
 * 事务管理器
 *
 * 支持事务传播行为和嵌套事务
 */
public class TransactionManager {
    // 使用 Stack 支持嵌套事务
    private ThreadLocal<Stack<TransactionStatus>> transactionStack = new ThreadLocal<>();

    /**
     * 根据传播行为开始事务
     */
    public TransactionStatus begin(Propagation propagation) {
        Stack<TransactionStatus> stack = getTransactionStack();
        TransactionStatus currentTx = stack.isEmpty() ? null : stack.peek();

        switch (propagation) {
            case REQUIRED:
                if (currentTx != null && currentTx.isActive()) {
                    System.out.println("   [TxManager] Joining existing transaction (REQUIRED)");
                    return currentTx;
                } else {
                    return createNewTransaction("REQUIRED");
                }

            case REQUIRES_NEW:
                if (currentTx != null && currentTx.isActive()) {
                    System.out.println("   [TxManager] Suspending current transaction, creating new one (REQUIRES_NEW)");
                    currentTx.setSuspended(true);
                }
                return createNewTransaction("REQUIRES_NEW");

            case NESTED:
                if (currentTx != null && currentTx.isActive()) {
                    System.out.println("   [TxManager] Creating nested transaction (NESTED)");
                    TransactionStatus nestedTx = new TransactionStatus();
                    nestedTx.setActive(true);
                    nestedTx.setNested(true);
                    nestedTx.setParent(currentTx);
                    stack.push(nestedTx);
                    return nestedTx;
                } else {
                    return createNewTransaction("NESTED");
                }

            case SUPPORTS:
                if (currentTx != null && currentTx.isActive()) {
                    System.out.println("   [TxManager] Joining existing transaction (SUPPORTS)");
                    return currentTx;
                } else {
                    System.out.println("   [TxManager] No transaction, executing without transaction (SUPPORTS)");
                    return null;
                }

            case NOT_SUPPORTED:
                if (currentTx != null && currentTx.isActive()) {
                    System.out.println("   [TxManager] Suspending current transaction (NOT_SUPPORTED)");
                    currentTx.setSuspended(true);
                }
                System.out.println("   [TxManager] Executing without transaction (NOT_SUPPORTED)");
                return null;

            case MANDATORY:
                if (currentTx != null && currentTx.isActive()) {
                    System.out.println("   [TxManager] Joining existing transaction (MANDATORY)");
                    return currentTx;
                } else {
                    throw new IllegalStateException("No existing transaction found (MANDATORY)");
                }

            case NEVER:
                if (currentTx != null && currentTx.isActive()) {
                    throw new IllegalStateException("Existing transaction found (NEVER)");
                }
                System.out.println("   [TxManager] Executing without transaction (NEVER)");
                return null;

            default:
                return createNewTransaction("REQUIRED");
        }
    }

    /**
     * 创建新事务
     */
    private TransactionStatus createNewTransaction(String propagation) {
        TransactionStatus status = new TransactionStatus();
        status.setActive(true);
        status.setNewTransaction(true);
        getTransactionStack().push(status);
        System.out.println("   [TxManager] Transaction started (" + propagation + ")");
        return status;
    }

    /**
     * 提交事务
     */
    public void commit(TransactionStatus status) {
        if (status == null) {
            return; // 无事务
        }

        Stack<TransactionStatus> stack = getTransactionStack();

        if (status.isNested()) {
            // 嵌套事务提交
            System.out.println("   [TxManager] Nested transaction committed");
            stack.pop();
        } else if (status.isNewTransaction()) {
            // 新事务提交
            status.setActive(false);
            System.out.println("   [TxManager] Transaction committed");
            stack.pop();

            // 恢复被挂起的事务
            if (!stack.isEmpty()) {
                TransactionStatus suspendedTx = stack.peek();
                if (suspendedTx.isSuspended()) {
                    suspendedTx.setSuspended(false);
                    System.out.println("   [TxManager] Resumed suspended transaction");
                }
            }
        }
    }

    /**
     * 回滚事务
     */
    public void rollback(TransactionStatus status) {
        if (status == null) {
            return; // 无事务
        }

        Stack<TransactionStatus> stack = getTransactionStack();

        if (status.isNested()) {
            // 嵌套事务回滚（不影响外部事务）
            System.out.println("   [TxManager] Nested transaction rolled back");
            stack.pop();
        } else if (status.isNewTransaction()) {
            // 新事务回滚
            status.setActive(false);
            System.out.println("   [TxManager] Transaction rolled back");
            stack.pop();

            // 恢复被挂起的事务
            if (!stack.isEmpty()) {
                TransactionStatus suspendedTx = stack.peek();
                if (suspendedTx.isSuspended()) {
                    suspendedTx.setSuspended(false);
                    System.out.println("   [TxManager] Resumed suspended transaction");
                }
            }
        }
    }

    public TransactionStatus getCurrentTransaction() {
        Stack<TransactionStatus> stack = transactionStack.get();
        return (stack == null || stack.isEmpty()) ? null : stack.peek();
    }

    private Stack<TransactionStatus> getTransactionStack() {
        Stack<TransactionStatus> stack = transactionStack.get();
        if (stack == null) {
            stack = new Stack<>();
            transactionStack.set(stack);
        }
        return stack;
    }

    /**
     * 事务状态
     */
    public static class TransactionStatus {
        private boolean active;
        private boolean newTransaction;
        private boolean nested;
        private boolean suspended;
        private TransactionStatus parent;

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public boolean isNewTransaction() {
            return newTransaction;
        }

        public void setNewTransaction(boolean newTransaction) {
            this.newTransaction = newTransaction;
        }

        public boolean isNested() {
            return nested;
        }

        public void setNested(boolean nested) {
            this.nested = nested;
        }

        public boolean isSuspended() {
            return suspended;
        }

        public void setSuspended(boolean suspended) {
            this.suspended = suspended;
        }

        public TransactionStatus getParent() {
            return parent;
        }

        public void setParent(TransactionStatus parent) {
            this.parent = parent;
        }
    }
}
