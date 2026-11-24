package com.spring.simulation.examples.transaction;

import com.spring.simulation.aop.AopAutoProxyCreator;
import com.spring.simulation.examples.transaction.H2OrderExperiment.AuditOperations;
import com.spring.simulation.examples.transaction.H2OrderExperiment.AuditService;
import com.spring.simulation.examples.transaction.H2OrderExperiment.Store;
import com.spring.simulation.ioc.annotation.Autowired;
import com.spring.simulation.ioc.factory.BeanDefinition;
import com.spring.simulation.ioc.factory.BeanFactory;
import com.spring.simulation.ioc.postprocessor.AutowiredAnnotationBeanPostProcessor;
import com.spring.simulation.transaction.TransactionManager;
import com.spring.simulation.transaction.Transactional;

/** 两张表先成功写入，再抛出指定异常，观察回滚规则对已写入数据的影响。 */
public class H2RollbackRuleExperiment implements AutoCloseable {
    public interface OrderRules {
        void defaults(Throwable failure) throws Throwable;
        void checkedRollback(Throwable failure) throws Throwable;
        void runtimeCommit(Throwable failure) throws Throwable;
        void narrowCommit(Throwable failure) throws Throwable;
        void narrowRollback(Throwable failure) throws Throwable;
        void tie(Throwable failure) throws Throwable;
        void unmatched(Throwable failure) throws Throwable;
        void classRule(Throwable failure) throws Throwable;
        @Transactional(noRollbackFor = IllegalArgumentException.class)
        void interfaceRule(Throwable failure) throws Throwable;
        void innerRollbackOnly(Throwable failure) throws Throwable;
    }

    @Transactional(rollbackFor = Exception.class)
    public static class OrderService implements OrderRules {
        @Autowired Store store;
        @Autowired AuditOperations audit;

        @Transactional
        public void defaults(Throwable failure) throws Throwable { writeAndThrow(failure); }
        @Transactional(rollbackFor = {ReflectiveOperationException.class, Exception.class})
        public void checkedRollback(Throwable failure) throws Throwable { writeAndThrow(failure); }
        @Transactional(noRollbackFor = {NullPointerException.class, RuntimeException.class})
        public void runtimeCommit(Throwable failure) throws Throwable { writeAndThrow(failure); }
        @Transactional(rollbackFor = Exception.class, noRollbackFor = IllegalArgumentException.class)
        public void narrowCommit(Throwable failure) throws Throwable { writeAndThrow(failure); }
        @Transactional(rollbackFor = IllegalArgumentException.class, noRollbackFor = Exception.class)
        public void narrowRollback(Throwable failure) throws Throwable { writeAndThrow(failure); }
        @Transactional(rollbackFor = IllegalArgumentException.class, noRollbackFor = IllegalArgumentException.class)
        public void tie(Throwable failure) throws Throwable { writeAndThrow(failure); }
        @Transactional(rollbackFor = ReflectiveOperationException.class, noRollbackFor = NullPointerException.class)
        public void unmatched(Throwable failure) throws Throwable { writeAndThrow(failure); }
        public void classRule(Throwable failure) throws Throwable { writeAndThrow(failure); }
        public void interfaceRule(Throwable failure) throws Throwable { writeAndThrow(failure); }

        @Transactional(noRollbackFor = IllegalArgumentException.class)
        public void innerRollbackOnly(Throwable ignored) throws Throwable {
            store.orderConnection = store.manager.getConnection();
            store.insert("INSERT INTO orders VALUES (?, '回滚标记实验')", 1);
            audit.required(1, true);
        }

        private void writeAndThrow(Throwable failure) throws Throwable {
            store.orderConnection = store.manager.getConnection();
            store.insert("INSERT INTO orders VALUES (?, '回滚规则实验')", 1);
            audit.required(1, false);
            if (failure != null) throw failure;
        }
    }

    public final H2OrderExperiment database = new H2OrderExperiment();
    private final BeanFactory factory;
    public final OrderRules orders;

    public H2RollbackRuleExperiment() throws Exception {
        try {
            factory = new BeanFactory() {
                @Override public TransactionManager getTransactionManager() { return database.manager; }
            };
            factory.registerSingleton("store", database.store);
            factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(factory));
            AopAutoProxyCreator creator = new AopAutoProxyCreator();
            creator.setBeanFactory(factory);
            factory.addBeanPostProcessor(creator);
            factory.registerBeanDefinition("audit", new BeanDefinition("audit", AuditService.class));
            factory.registerBeanDefinition("ruleOrders", new BeanDefinition("ruleOrders", OrderService.class));
            orders = (OrderRules) factory.getBean("ruleOrders");
        } catch (Exception failure) {
            try { database.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        try { factory.destroySingletons(); } finally { database.close(); }
    }

    public static void main(String[] args) throws Exception {
        String[] methods = {"defaults", "defaults", "checkedRollback", "runtimeCommit"};
        Throwable[] failures = {new Exception("受检异常"), new IllegalArgumentException("运行时异常"),
                new Exception("受检异常"), new IllegalArgumentException("运行时异常")};
        for (int i = 0; i < methods.length; i++) {
            try (H2RollbackRuleExperiment experiment = new H2RollbackRuleExperiment()) {
                try { OrderRules.class.getMethod(methods[i], Throwable.class).invoke(experiment.orders, failures[i]); }
                catch (ReflectiveOperationException exception) {
                    System.out.println("调用方收到异常：" + exception.getCause());
                }
                System.out.printf("回滚规则实验：%s / %s → 订单=%d，审计=%d%n", methods[i], failures[i].getClass().getSimpleName(),
                        experiment.database.count(false, 1), experiment.database.count(true, 1));
            }
        }
    }
}
