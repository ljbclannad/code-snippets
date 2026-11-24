package com.spring.simulation;

import com.spring.simulation.examples.transaction.H2RollbackRuleExperiment;
import com.spring.simulation.examples.transaction.H2RollbackRuleExperiment.OrderRules;
import java.lang.reflect.InvocationTargetException;
import java.io.IOException;

/** 所有普通用例都先写入订单和审计，再抛异常；用独立连接断言提交结果。 */
public class RollbackRuleRegressionTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void scenario(String label, String method, Throwable failure, int expectedRows) throws Exception {
        try (H2RollbackRuleExperiment experiment = new H2RollbackRuleExperiment()) {
            Throwable caught = null;
            try { OrderRules.class.getMethod(method, Throwable.class).invoke(experiment.orders, failure); }
            catch (InvocationTargetException exception) { caught = exception.getCause(); }
            if (method.equals("innerRollbackOnly")) {
                check(caught instanceof IllegalStateException && caught.getMessage().contains("rollback-only"),
                        "noRollbackFor 不能清除已有回滚标记");
                check(caught.getSuppressed().length == 1
                        && caught.getSuppressed()[0] instanceof IllegalArgumentException,
                        "完成失败应保留原业务异常");
            } else {
                check(caught == failure, "业务异常应按原对象传播，包括提交后传播");
            }
            check(experiment.database.count(false, 1) == expectedRows, label + "：订单结果错误");
            check(experiment.database.count(true, 1) == expectedRows, label + "：审计结果错误");
            check(experiment.database.store.orderConnection == experiment.database.store.auditConnection,
                    "订单与 REQUIRED 审计应使用同一连接");
            check(experiment.database.store.orderConnection.isClosed(), "完成后连接未关闭");
            check(experiment.database.manager.getCurrentTransaction() == null, "事务状态泄漏");
            try {
                experiment.database.manager.getConnection();
                throw new AssertionError("完成后不应仍能获取事务连接");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("当前没有 JDBC 事务"), "连接清理异常不符合预期");
            }
            System.err.printf("通过：回滚规则 %s → 订单=%d，审计=%d%n", label, expectedRows, expectedRows);
        }
    }

    public static void main(String[] args) throws Exception {
        scenario("正常返回", "defaults", null, 1);
        scenario("默认受检异常提交/方法注解覆盖类注解", "defaults", new Exception("受检失败"), 1);
        scenario("默认运行时异常回滚", "defaults", new IllegalArgumentException("运行时失败"), 0);
        scenario("默认 Error 回滚", "defaults", new AssertionError("业务错误"), 0);
        scenario("rollbackFor 匹配受检子类", "checkedRollback", new IOException("受检子类"), 0);
        scenario("noRollbackFor 匹配运行时子类", "runtimeCommit", new IllegalArgumentException("运行时子类"), 1);
        scenario("更具体的不回滚规则胜出", "narrowCommit", new IllegalArgumentException("具体异常"), 1);
        scenario("更具体的回滚规则胜出", "narrowRollback", new IllegalArgumentException("具体异常"), 0);
        scenario("同类型冲突优先回滚", "tie", new IllegalArgumentException("相同规则"), 0);
        scenario("无匹配规则回退受检默认值", "unmatched", new Exception("未匹配"), 1);
        scenario("无匹配规则回退运行时默认值", "unmatched", new IllegalArgumentException("未匹配"), 0);
        scenario("类级规则", "classRule", new Exception("类级异常"), 0);
        scenario("接口方法规则", "interfaceRule", new IllegalArgumentException("接口异常"), 1);
        scenario("已有 rollback-only 不可被清除", "innerRollbackOnly", null, 0);
        System.err.println("回滚规则 H2 回归通过：14 组");
    }
}
