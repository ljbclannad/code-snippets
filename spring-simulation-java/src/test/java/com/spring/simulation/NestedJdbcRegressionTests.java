package com.spring.simulation;

import com.spring.simulation.transaction.JdbcTransactionManager;
import com.spring.simulation.transaction.Propagation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** 保存点边界与故障注入：底层仍使用真实 H2 连接。 */
public class NestedJdbcRegressionTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalStateException expected) {
            check(expected.getMessage().contains(message), "异常不符合预期：" + expected);
            return;
        }
        throw new AssertionError("预期异常：" + message);
    }

    private static class Database implements AutoCloseable {
        final String url = "jdbc:h2:mem:nested_" + UUID.randomUUID();
        final Connection observer = DriverManager.getConnection(url);
        String failingOperation;
        int savepoints;
        int releases;
        final JdbcTransactionManager manager = new JdbcTransactionManager(this::open);

        Database() throws SQLException {
            try (Statement statement = observer.createStatement()) {
                statement.execute("CREATE TABLE orders(id INT PRIMARY KEY)");
            }
        }

        Connection open() throws SQLException {
            Connection actual = DriverManager.getConnection(url);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        boolean savepointRollback = method.getName().equals("rollback") && args != null && args.length == 1;
                        String operation = savepointRollback ? "rollbackSavepoint" : method.getName();
                        if (operation.equals(failingOperation)) throw new SQLException("模拟保存点故障：" + operation);
                        try {
                            Object result = method.invoke(actual, args);
                            if (operation.equals("setSavepoint")) savepoints++;
                            if (operation.equals("releaseSavepoint")) releases++;
                            return result;
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }

        void insert(int id) throws SQLException {
            try (Statement statement = manager.getConnection().createStatement()) {
                statement.executeUpdate("INSERT INTO orders VALUES (" + id + ")");
            }
        }

        int count(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM orders")) {
                result.next();
                return result.getInt(1);
            }
        }

        void clean(Connection connection) throws SQLException {
            check(connection.isClosed(), "事务连接未关闭");
            check(manager.getCurrentTransaction() == null, "事务状态未清理");
            rejects(() -> manager.getConnection(), "当前没有 JDBC 事务");
        }

        public void close() throws SQLException { observer.close(); }
    }

    private static void withoutOuter() throws Exception {
        try (Database db = new Database()) {
            var status = db.manager.begin(Propagation.NESTED);
            Connection connection = db.manager.getConnection();
            check(status.isNewTransaction() && !status.isNested(), "无外层时应开启普通事务");
            db.insert(1);
            db.manager.commit(status);
            db.clean(connection);
            check(db.count(db.observer) == 1 && db.savepoints == 0, "无外层提交结果错误");
            var rollback = db.manager.begin(Propagation.NESTED);
            connection = db.manager.getConnection();
            db.insert(2);
            db.manager.rollback(rollback);
            check(db.count(db.observer) == 1, "无外层回滚结果错误");
            db.clean(connection);
        }
        System.err.println("通过：NESTED 无外层事务的提交与回滚");
    }

    private static void multipleSavepoints() throws Exception {
        try (Database db = new Database()) {
            var outer = db.manager.begin(Propagation.REQUIRED);
            Connection connection = db.manager.getConnection();
            db.insert(1);
            var middle = db.manager.begin(Propagation.NESTED);
            db.insert(2);
            var inner = db.manager.begin(Propagation.NESTED);
            db.insert(3);
            rejects(() -> db.manager.commit(middle), "逆序");
            db.manager.rollback(inner);
            check(db.count(connection) == 2, "最内层回滚应保留外层与中层写入");
            check(db.manager.getCurrentTransaction() == middle, "未恢复中层状态");
            db.manager.commit(middle);
            rejects(() -> db.manager.commit(middle), "只能完成一次");
            check(db.manager.getCurrentTransaction() == outer && db.manager.getConnection() == connection,
                    "未恢复外层事务与连接");
            check(db.count(db.observer) == 0 && !connection.isClosed(), "释放保存点不能提交或关闭连接");
            check(db.savepoints == 2 && db.releases == 2, "保存点未配对释放");
            db.insert(4);
            db.manager.commit(outer);
            check(db.count(db.observer) == 3, "外层继续写入并提交的结果错误");
            db.clean(connection);
        }
        System.err.println("通过：多层保存点局部回滚、释放与外层继续写入");
    }

    private static void requiredInsideNested() throws Exception {
        try (Database db = new Database()) {
            var outer = db.manager.begin(Propagation.REQUIRED);
            Connection connection = db.manager.getConnection();
            db.insert(1);
            var nested = db.manager.begin(Propagation.NESTED);
            db.insert(2);
            var participant = db.manager.begin(Propagation.REQUIRED);
            db.insert(3);
            db.manager.rollback(participant);
            rejects(() -> db.manager.commit(nested), "rollback-only");
            check(!outer.isRollbackOnly() && db.count(connection) == 1, "嵌套回滚标记应只回滚保存点范围");
            check(db.releases == 1, "回滚后保存点未释放");
            db.manager.commit(outer);
            check(db.count(db.observer) == 1, "外层应可正常提交");
            db.clean(connection);
        }
        System.err.println("通过：NESTED 内 REQUIRED 失败回滚到保存点");
    }

    private static void savepointFailure(String operation) throws Exception {
        try (Database db = new Database()) {
            var outer = db.manager.begin(Propagation.REQUIRED);
            Connection connection = db.manager.getConnection();
            db.insert(1);
            if (operation.equals("setSavepoint")) {
                db.failingOperation = operation;
                rejects(() -> db.manager.begin(Propagation.NESTED), "开启 JDBC 事务失败");
                check(!outer.isRollbackOnly(), "创建保存点失败不应撤销外层已有写入");
            } else {
                var nested = db.manager.begin(Propagation.NESTED);
                db.insert(2);
                db.failingOperation = operation;
                if (operation.equals("rollbackSavepoint")) {
                    rejects(() -> db.manager.rollback(nested), "完成 JDBC 事务失败");
                } else {
                    rejects(() -> db.manager.commit(nested), "完成 JDBC 事务失败");
                }
                check(nested.isCompleted() && outer.isRollbackOnly(), "保存点完成失败必须禁止外层误提交");
            }
            check(db.manager.getCurrentTransaction() == outer && db.manager.getConnection() == connection,
                    "保存点失败后未恢复外层");
            db.failingOperation = null;
            if (operation.equals("setSavepoint")) {
                // 恢复驱动后，同一外层事务仍可创建并完成新的保存点。
                var retry = db.manager.begin(Propagation.NESTED);
                db.insert(3);
                db.manager.commit(retry);
                db.manager.commit(outer);
                check(db.count(db.observer) == 2, "创建失败后的重试/外层提交失败");
            } else {
                rejects(() -> db.manager.commit(outer), "rollback-only");
                check(db.count(db.observer) == 0, "保存点完成失败后不应留下数据");
            }
            db.clean(connection);
        }
        System.err.println("通过：保存点故障处理 " + operation);
    }

    public static void main(String[] args) throws Exception {
        withoutOuter();
        multipleSavepoints();
        requiredInsideNested();
        savepointFailure("setSavepoint");
        savepointFailure("rollbackSavepoint");
        savepointFailure("releaseSavepoint");
        System.err.println("NESTED 保存点专项回归通过：6 组");
    }
}
