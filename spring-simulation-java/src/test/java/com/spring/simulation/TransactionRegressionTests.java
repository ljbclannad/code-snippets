package com.spring.simulation;

import com.spring.simulation.aop.AopAutoProxyCreator;
import com.spring.simulation.ioc.annotation.Autowired;
import com.spring.simulation.ioc.factory.BeanDefinition;
import com.spring.simulation.ioc.factory.BeanFactory;
import com.spring.simulation.ioc.postprocessor.AutowiredAnnotationBeanPostProcessor;
import com.spring.simulation.transaction.TransactionManager;
import com.spring.simulation.transaction.TransactionManager.TransactionStatus;
import com.spring.simulation.transaction.Transactional;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** 跨 Bean 的事务传播回归实验；仅验证状态与完成调用，不模拟数据库写入。 */
public class TransactionRegressionTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 先执行真实状态转换，再记录结果，避免用日志判断事务是否完成。 */
    static class RecordingManager extends TransactionManager {
        final List<String> completions = new ArrayList<>();

        @Override
        public void commit(TransactionStatus status) {
            try {
                super.commit(status);
                completions.add(status.isNewTransaction() ? "独立事务提交" : "参与者返回");
            } catch (IllegalStateException exception) {
                if (status.isCompleted() && status.isRollbackOnly()) completions.add("事务因回滚标记回滚");
                throw exception;
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            super.rollback(status);
            completions.add(status.isNewTransaction() ? "独立事务回滚" : "参与者标记回滚");
        }
    }

    public static class Observation {
        final RecordingManager manager;
        TransactionStatus outer;
        TransactionStatus inner;
        boolean returned;
        boolean caught;

        Observation(RecordingManager manager) { this.manager = manager; }
    }

    public interface InnerOperations {
        void required(boolean fail);
        void requiresNew(boolean fail);
    }

    public static class InnerService implements InnerOperations {
        @Autowired Observation observation;

        @Transactional
        public void required(boolean fail) {
            observe(false, fail);
        }

        @Transactional(propagation = "REQUIRES_NEW")
        public void requiresNew(boolean fail) {
            observe(true, fail);
        }

        private void observe(boolean independent, boolean fail) {
            observation.inner = observation.manager.getCurrentTransaction();
            check(observation.inner != null && observation.inner.isActive(), "内层事务应活跃");
            check((observation.inner != observation.outer) == independent, "内外层事务归属错误");
            check(observation.outer.isSuspended() == independent, "外层挂起状态错误");
            if (fail) throw new IllegalArgumentException("内层失败");
        }
    }

    public interface OuterOperations {
        void run(boolean independent, boolean innerFails);
    }

    public static class OuterService implements OuterOperations {
        @Autowired InnerOperations innerService;
        @Autowired Observation observation;

        @Transactional
        public void run(boolean independent, boolean innerFails) {
            observation.outer = observation.manager.getCurrentTransaction();
            check(observation.outer != null && observation.outer.isActive(), "外层事务未开启");
            try {
                if (independent) innerService.requiresNew(innerFails);
                else innerService.required(innerFails);
                observation.returned = true;
            } catch (IllegalArgumentException exception) {
                check(innerFails && "内层失败".equals(exception.getMessage()), "异常不符合预期");
                observation.caught = true;
            }
            check(observation.manager.getCurrentTransaction() == observation.outer, "未恢复外层事务");
            check(observation.outer.isActive() && !observation.outer.isSuspended(), "外层过早完成或仍挂起");
            check(observation.outer.isRollbackOnly() == (innerFails && !independent), "外层回滚标记错误");
            check(observation.inner.isCompleted() == independent, "内层返回后的事务完成状态错误");
            if (!innerFails) throw new IllegalStateException("外层失败");
        }
    }

    private static void experiment(boolean independent, boolean innerFails, List<String> expected) throws Exception {
        RecordingManager manager = new RecordingManager();
        BeanFactory factory = new BeanFactory() {
            @Override public TransactionManager getTransactionManager() { return manager; }
        };
        Observation observation = new Observation(manager);
        factory.registerSingleton("observation", observation);
        factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(factory));
        AopAutoProxyCreator creator = new AopAutoProxyCreator();
        creator.setBeanFactory(factory);
        factory.addBeanPostProcessor(creator);
        factory.registerBeanDefinition("innerService", new BeanDefinition("innerService", InnerService.class));
        factory.registerBeanDefinition("outerService", new BeanDefinition("outerService", OuterService.class));
        try {
            OuterOperations outer = (OuterOperations) factory.getBean("outerService");
            check(Proxy.isProxyClass(outer.getClass()), "外层必须经过代理");
            check(Proxy.isProxyClass(factory.getBean("innerService").getClass()), "内层必须经过代理");
            String failure = null;
            try {
                outer.run(independent, innerFails);
            } catch (IllegalStateException exception) {
                failure = exception.getMessage();
            }
            if (!innerFails) check("外层失败".equals(failure), "外层异常应原样传播");
            else if (!independent) check(failure != null && failure.contains("rollback-only"), "捕获内层异常后仍应拒绝提交");
            else check(failure == null, "独立内层回滚不应阻止外层提交");
            check(observation.returned == !innerFails && observation.caught == innerFails, "内层返回路径错误");
            check(manager.completions.equals(expected), "完成顺序错误：" + manager.completions);
            check(observation.outer.isCompleted() && !observation.outer.isActive(), "外层事务未完成");
            check(observation.inner.isCompleted() && !observation.inner.isActive(), "内层事务未完成");
            check(manager.getCurrentTransaction() == null, "事务上下文泄漏");
            System.err.println("通过：" + (independent ? "REQUIRES_NEW" : "REQUIRED")
                    + (innerFails ? " 内层异常被捕获" : " 内层返回后外层回滚") + " → " + manager.completions);
        } finally {
            factory.destroySingletons();
        }
    }

    public static void main(String[] args) throws Exception {
        experiment(false, false, List.of("参与者返回", "独立事务回滚"));
        experiment(true, false, List.of("独立事务提交", "独立事务回滚"));
        experiment(false, true, List.of("参与者标记回滚", "事务因回滚标记回滚"));
        experiment(true, true, List.of("独立事务回滚", "独立事务提交"));
        System.err.println("事务回归实验通过：4 组");
    }
}
