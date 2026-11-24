package com.spring.simulation.transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** 教学用 JDBC 扩展，支持 REQUIRED、REQUIRES_NEW 与 NESTED；连接由管理器持有并关闭。 */
public class JdbcTransactionManager extends TransactionManager {
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    private static class Frame {
        final TransactionStatus status;
        final Connection connection;
        final Savepoint savepoint;
        Frame(TransactionStatus status, Connection connection, Savepoint savepoint) {
            this.status = status;
            this.connection = connection;
            this.savepoint = savepoint;
        }
    }

    private final ConnectionFactory connectionFactory;
    private final ThreadLocal<Deque<Frame>> frames = ThreadLocal.withInitial(ArrayDeque::new);

    public JdbcTransactionManager(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
    }

    @Override
    public TransactionStatus begin(Propagation propagation) {
        if (propagation != Propagation.REQUIRED && propagation != Propagation.REQUIRES_NEW
                && propagation != Propagation.NESTED) {
            throw new IllegalArgumentException("JDBC 实验仅支持 REQUIRED、REQUIRES_NEW 和 NESTED");
        }
        TransactionStatus status = super.begin(propagation);
        Connection connection = null;
        try {
            if (status.isNewTransaction()) {
                connection = connectionFactory.open();
                connection.setAutoCommit(false);
            } else {
                connection = frames.get().peek().connection;
            }
            Savepoint savepoint = status.isNested() ? connection.setSavepoint() : null;
            frames.get().push(new Frame(status, connection, savepoint));
            return status;
        } catch (SQLException | RuntimeException exception) {
            if (connection != null && status.isNewTransaction()) {
                try { connection.close(); } catch (SQLException closeFailure) { exception.addSuppressed(closeFailure); }
            }
            super.rollback(status);
            if (frames.get().isEmpty()) frames.remove();
            throw new IllegalStateException("开启 JDBC 事务失败", exception);
        }
    }

    /** 业务代码只能关闭 Statement / ResultSet，不能自行提交或关闭此连接。 */
    public Connection getConnection() {
        Frame frame = frames.get().peek();
        if (frame == null) {
            frames.remove();
            throw new IllegalStateException("当前没有 JDBC 事务");
        }
        return frame.connection;
    }

    @Override
    public void commit(TransactionStatus status) { finish(status, false); }

    @Override
    public void rollback(TransactionStatus status) { finish(status, true); }

    private void finish(TransactionStatus status, boolean rollback) {
        if (status == null) return;
        Deque<Frame> stack = frames.get();
        Frame frame = stack.peek();
        if (frame == null) frames.remove();
        if (frame == null || frame.status != status || status.isCompleted()) {
            throw new IllegalStateException("JDBC 事务必须按开启的逆序完成且只能完成一次");
        }
        RuntimeException failure = null;
        try {
            if (frame.savepoint != null) {
                if (rollback || status.isRollbackOnly()) frame.connection.rollback(frame.savepoint);
                frame.connection.releaseSavepoint(frame.savepoint);
            } else if (status.isNewTransaction()) {
                if (rollback || status.isRollbackOnly()) frame.connection.rollback();
                else frame.connection.commit();
            }
            if (rollback) super.rollback(status);
            else super.commit(status);
        } catch (SQLException exception) {
            failure = new IllegalStateException("完成 JDBC 事务失败", exception);
            if (frame.savepoint != null) {
                // 保存点完成失败后不能保证局部回滚；禁止同连接的任何外层作用域提交。
                for (Frame active : stack) {
                    if (active.connection == frame.connection) markRollbackOnly(active.status);
                }
            } else {
                // 独立事务提交失败时尝试回滚；保留主异常及清理异常。
                try { frame.connection.rollback(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            }
            if (!status.isCompleted()) super.rollback(status);
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            stack.pop();
            if (stack.isEmpty()) frames.remove();
            if (status.isNewTransaction()) {
                try { frame.connection.close(); } catch (SQLException exception) {
                    if (failure == null) failure = new IllegalStateException("关闭 JDBC 连接失败", exception);
                    else failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) throw failure;
    }
}
