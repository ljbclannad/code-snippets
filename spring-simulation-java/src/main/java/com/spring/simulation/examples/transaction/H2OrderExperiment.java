package com.spring.simulation.examples.transaction;

import com.spring.simulation.aop.AopAutoProxyCreator;
import com.spring.simulation.ioc.annotation.Autowired;
import com.spring.simulation.ioc.factory.BeanDefinition;
import com.spring.simulation.ioc.factory.BeanFactory;
import com.spring.simulation.ioc.postprocessor.AutowiredAnnotationBeanPostProcessor;
import com.spring.simulation.transaction.JdbcTransactionManager;
import com.spring.simulation.transaction.Propagation;
import com.spring.simulation.transaction.TransactionManager;
import com.spring.simulation.transaction.Transactional;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** 创建订单与写审计记录的真实数据库实验，使用项目自身的容器和事务代理。 */
public class H2OrderExperiment implements AutoCloseable {
    public enum Outcome { SUCCESS, OUTER_FAILURE, INNER_FAILURE_CAUGHT }

    public static class Store {
        final JdbcTransactionManager manager;
        public Connection orderConnection;
        public Connection auditConnection;
        public boolean resumed;
        Store(JdbcTransactionManager manager) { this.manager = manager; }

        void insert(String sql, int orderId) throws SQLException {
            try (PreparedStatement statement = manager.getConnection().prepareStatement(sql)) {
                statement.setInt(1, orderId);
                statement.executeUpdate();
            }
        }
    }

    public interface AuditOperations {
        void required(int orderId, boolean fail) throws SQLException;
        void requiresNew(int orderId, boolean fail) throws SQLException;
        void nested(int orderId, boolean fail) throws SQLException;
    }

    public static class AuditService implements AuditOperations {
        @Autowired Store store;
        @Transactional(rollbackFor = SQLException.class)
        public void required(int orderId, boolean fail) throws SQLException { write(orderId, fail); }
        @Transactional(propagation = "REQUIRES_NEW", rollbackFor = SQLException.class)
        public void requiresNew(int orderId, boolean fail) throws SQLException { write(orderId, fail); }
        @Transactional(propagation = "NESTED", rollbackFor = SQLException.class)
        public void nested(int orderId, boolean fail) throws SQLException { write(orderId, fail); }
        private void write(int orderId, boolean fail) throws SQLException {
            store.auditConnection = store.manager.getConnection();
            store.insert("INSERT INTO audit_records(order_id, message) VALUES (?, '尝试创建订单')", orderId);
            if (fail) throw new IllegalArgumentException("审计写入后失败");
        }
    }

    public interface OrderOperations {
        void create(int orderId, Propagation auditPropagation, Outcome outcome) throws SQLException;
    }

    public static class OrderService implements OrderOperations {
        @Autowired Store store;
        @Autowired AuditOperations audit;
        @Transactional(rollbackFor = SQLException.class)
        public void create(int orderId, Propagation auditPropagation, Outcome outcome) throws SQLException {
            if (auditPropagation != Propagation.REQUIRED && auditPropagation != Propagation.REQUIRES_NEW
                    && auditPropagation != Propagation.NESTED) throw new IllegalArgumentException("不支持的审计传播行为");
            store.orderConnection = store.manager.getConnection();
            store.insert("INSERT INTO orders(id, description) VALUES (?, '示例订单')", orderId);
            try {
                if (auditPropagation == Propagation.REQUIRES_NEW) audit.requiresNew(orderId, outcome == Outcome.INNER_FAILURE_CAUGHT);
                else if (auditPropagation == Propagation.NESTED) audit.nested(orderId, outcome == Outcome.INNER_FAILURE_CAUGHT);
                else audit.required(orderId, outcome == Outcome.INNER_FAILURE_CAUGHT);
            } catch (IllegalArgumentException exception) {
                if (outcome != Outcome.INNER_FAILURE_CAUGHT) throw exception;
            }
            store.resumed = store.manager.getConnection() == store.orderConnection;
            if (outcome == Outcome.OUTER_FAILURE) throw new IllegalStateException("订单创建失败");
        }
    }

    private final String url = "jdbc:h2:mem:orders_" + UUID.randomUUID() + ";LOCK_TIMEOUT=2000";
    // 保持一个连接存活来维持内存数据库，实验关闭后数据库自动释放。
    private final Connection keeper;
    public final JdbcTransactionManager manager;
    public final Store store;
    private final BeanFactory factory;
    public final OrderOperations orders;

    public H2OrderExperiment() throws Exception {
        keeper = DriverManager.getConnection(url);
        try {
            try (Statement statement = keeper.createStatement()) {
                statement.execute("CREATE TABLE orders(id INT PRIMARY KEY, description VARCHAR(100))");
                statement.execute("CREATE TABLE audit_records(order_id INT PRIMARY KEY, message VARCHAR(100))");
            }
            manager = new JdbcTransactionManager(() -> DriverManager.getConnection(url));
            store = new Store(manager);
            factory = new BeanFactory() {
                @Override public TransactionManager getTransactionManager() { return manager; }
            };
            factory.registerSingleton("store", store);
            factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(factory));
            AopAutoProxyCreator proxyCreator = new AopAutoProxyCreator();
            proxyCreator.setBeanFactory(factory);
            factory.addBeanPostProcessor(proxyCreator);
            factory.registerBeanDefinition("audit", new BeanDefinition("audit", AuditService.class));
            factory.registerBeanDefinition("orders", new BeanDefinition("orders", OrderService.class));
            orders = (OrderOperations) factory.getBean("orders");
        } catch (Exception exception) {
            try { keeper.close(); } catch (SQLException cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        }
    }

    /** 用全新连接查询，避免把事务内部可见但尚未提交的数据当作实验结果。 */
    public int count(boolean audit, int orderId) throws SQLException {
        String sql = audit ? "SELECT COUNT(*) FROM audit_records WHERE order_id = ?"
                : "SELECT COUNT(*) FROM orders WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, orderId);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    @Override
    public void close() throws Exception {
        try { factory.destroySingletons(); } finally { keeper.close(); }
    }

    public static void main(String[] args) throws Exception {
        for (Propagation propagation : new Propagation[]{Propagation.REQUIRED, Propagation.REQUIRES_NEW, Propagation.NESTED}) {
            for (Outcome outcome : Outcome.values()) {
                try (H2OrderExperiment experiment = new H2OrderExperiment()) {
                    try { experiment.orders.create(1, propagation, outcome); }
                    catch (IllegalStateException exception) { System.out.println("业务结果：" + exception.getMessage()); }
                    System.out.printf("实验结果：%s / %s → 订单=%d，审计=%d%n",
                            propagation, outcome,
                            experiment.count(false, 1), experiment.count(true, 1));
                }
            }
        }
    }
}
