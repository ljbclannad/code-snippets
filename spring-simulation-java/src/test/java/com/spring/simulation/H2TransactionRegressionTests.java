package com.spring.simulation;

import com.spring.simulation.examples.transaction.H2OrderExperiment;
import com.spring.simulation.examples.transaction.H2OrderExperiment.Outcome;
import com.spring.simulation.transaction.JdbcTransactionManager;
import com.spring.simulation.transaction.Propagation;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/** 断言已提交数据、连接归属及资源释放，不以日志代替验证。 */
public class H2TransactionRegressionTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalStateException expected) {
            check(expected.getMessage().contains(message), "异常不符合预期：" + expected);
            return;
        }
        throw new AssertionError("预期失败：" + message);
    }

    private static void business(Propagation propagation, Outcome outcome) throws Exception {
        try (H2OrderExperiment experiment = new H2OrderExperiment()) {
            String failure = null;
            try { experiment.orders.create(1, propagation, outcome); }
            catch (IllegalStateException exception) { failure = exception.getMessage(); }
            if (outcome == Outcome.OUTER_FAILURE) check("订单创建失败".equals(failure), "外层异常应原样传播");
            else if (outcome == Outcome.INNER_FAILURE_CAUGHT && propagation == Propagation.REQUIRED) {
                check(failure != null && failure.contains("rollback-only"), "共享事务应拒绝提交");
            } else check(failure == null, "不应出现异常：" + failure);

            int expectedOrders = outcome == Outcome.SUCCESS ||
                    (propagation != Propagation.REQUIRED && outcome == Outcome.INNER_FAILURE_CAUGHT) ? 1 : 0;
            int expectedAudit = outcome == Outcome.SUCCESS ||
                    (propagation == Propagation.REQUIRES_NEW && outcome == Outcome.OUTER_FAILURE) ? 1 : 0;
            check(experiment.count(false, 1) == expectedOrders, "订单持久化结果错误");
            check(experiment.count(true, 1) == expectedAudit, "审计持久化结果错误");
            check((experiment.store.orderConnection != experiment.store.auditConnection) == (propagation == Propagation.REQUIRES_NEW),
                    "内外层连接归属错误");
            check(experiment.store.resumed, "内层完成后未恢复外层连接");
            check(experiment.store.orderConnection.isClosed(), "订单连接未关闭");
            check(experiment.store.auditConnection.isClosed(), "审计连接未关闭");
            check(experiment.manager.getCurrentTransaction() == null, "事务状态泄漏");
            rejects(() -> experiment.manager.getConnection(), "当前没有 JDBC 事务");
            // 同一线程、同一容器继续执行，确认失败没有污染下一笔事务。
            experiment.orders.create(2, propagation, Outcome.SUCCESS);
            check(experiment.count(false, 2) == 1 && experiment.count(true, 2) == 1, "后续事务未正常提交");
            check(experiment.manager.getCurrentTransaction() == null, "后续事务状态泄漏");
            System.err.printf("通过：H2 %s / %s → 订单=%d，审计=%d%n",
                    propagation, outcome, expectedOrders, expectedAudit);
        }
    }

    private static void completionOrder() throws Exception {
        JdbcTransactionManager manager = new JdbcTransactionManager(() -> DriverManager.getConnection("jdbc:h2:mem:completion"));
        var outer = manager.begin(Propagation.REQUIRED);
        Connection connection = manager.getConnection();
        var inner = manager.begin(Propagation.REQUIRED);
        rejects(() -> manager.commit(outer), "逆序");
        check(!connection.isClosed() && outer.isActive(), "乱序完成不应改变事务");
        manager.commit(inner);
        check(!connection.isClosed(), "参与者返回不应关闭连接");
        manager.rollback(outer);
        rejects(() -> manager.commit(outer), "只能完成一次");
        check(connection.isClosed() && manager.getCurrentTransaction() == null, "完成后资源未清理");
        System.err.println("通过：JDBC 乱序与重复完成保护");
    }

    private static void beginFailure() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        JdbcTransactionManager manager = new JdbcTransactionManager(() -> {
            if (opens.incrementAndGet() == 2) throw new SQLException("模拟连接失败");
            return DriverManager.getConnection("jdbc:h2:mem:begin_failure");
        });
        var outer = manager.begin(Propagation.REQUIRED);
        Connection connection = manager.getConnection();
        rejects(() -> manager.begin(Propagation.REQUIRES_NEW), "开启 JDBC 事务失败");
        check(manager.getCurrentTransaction() == outer && outer.isActive() && !outer.isSuspended(), "开启失败未恢复外层");
        check(manager.getConnection() == connection && !connection.isClosed(), "外层连接被破坏");
        manager.commit(outer);
        check(connection.isClosed() && manager.getCurrentTransaction() == null, "外层未正常完成");
        System.err.println("通过：REQUIRES_NEW 开启失败恢复外层");
    }

    private static void sqlFailure() throws Exception {
        try (H2OrderExperiment experiment = new H2OrderExperiment()) {
            experiment.orders.create(1, Propagation.REQUIRED, Outcome.SUCCESS);
            try {
                experiment.orders.create(1, Propagation.REQUIRES_NEW, Outcome.SUCCESS);
                throw new AssertionError("重复订单主键应失败");
            } catch (SQLException expected) {
                check("23505".equals(expected.getSQLState()), "应为主键冲突");
            }
            check(experiment.count(false, 1) == 1 && experiment.count(true, 1) == 1, "SQL 异常破坏已有数据");
            check(experiment.store.orderConnection.isClosed(), "SQL 异常后连接未关闭");
            check(experiment.manager.getCurrentTransaction() == null, "SQL 异常后事务未清理");
        }
        System.err.println("通过：真实 SQL 异常回滚与清理");
    }

    public static void main(String[] args) throws Exception {
        for (Propagation propagation : new Propagation[]{Propagation.REQUIRED, Propagation.REQUIRES_NEW, Propagation.NESTED}) {
            for (Outcome outcome : Outcome.values()) business(propagation, outcome);
        }
        completionOrder();
        beginFailure();
        sqlFailure();
        NestedJdbcRegressionTests.main(args);
        System.err.println("H2 事务回归通过：18 组");
    }
}
