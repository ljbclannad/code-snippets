package com.lx.governance.order.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.lx.governance.order.domain.OrderProjectionEvent;
import com.lx.governance.order.domain.PaymentProjectionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class OrderAdminReadModelRepositoryIntegrationTest {
    private static final String ORDER_NO_PREFIX = "LOCAL-MYSQL-PROJECTION-";

    private JdbcTemplate jdbcTemplate;
    private OrderAdminReadModelRepository repository;
    private TransactionTemplate transactionTemplate;
    private String orderNo;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl("jdbc:mysql://%s:%s/order_db?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useAffectedRows=true"
                .formatted(env("MYSQL_HOST", "127.0.0.1"), env("MYSQL_PORT", "3306")));
        dataSource.setUsername(env("MYSQL_USER", "root"));
        dataSource.setPassword(env("MYSQL_PASS", "root"));

        jdbcTemplate = new JdbcTemplate(dataSource);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'order_db' AND table_name = 't_order_admin_read_model'
                """, Integer.class)).isEqualTo(1);
        repository = new OrderAdminReadModelRepository(jdbcTemplate);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        orderNo = ORDER_NO_PREFIX + UUID.randomUUID();
    }

    @AfterEach
    void removesLocalMysqlVerificationData() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_order_admin_read_model WHERE order_no = ?",
                Integer.class, orderNo)).isZero();
    }

    @Test
    void projectsNewestOrderSnapshotAndRollsBackVerificationData() {
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(repository.upsertOrderIfNewer(event("event-created", "CREATED", 1, 2, 100))).isEqualTo(1);
            assertThat(repository.upsertOrderIfNewer(event("event-paid", "PAID", 2, 3, 150))).isEqualTo(2);
            assertThat(repository.upsertOrderIfNewer(event("event-cancelled", "CANCELLED", 3, 3, 150))).isEqualTo(2);

            assertProjection("CANCELLED", 3, "event-cancelled", 3, 150);
            status.setRollbackOnly();
        });
    }

    @Test
    void ignoresDuplicateAndOlderOrderEvents() {
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(repository.upsertOrderIfNewer(event("event-cancelled", "CANCELLED", 3, 3, 150))).isEqualTo(1);
            assertThat(repository.upsertOrderIfNewer(event("event-duplicate", "PAID", 3, 88, 8888))).isZero();
            assertThat(repository.upsertOrderIfNewer(event("event-old", "PAID", 2, 99, 9999))).isZero();

            assertProjection("CANCELLED", 3, "event-cancelled", 3, 150);
            status.setRollbackOnly();
        });
    }

    @Test
    void preservesPaymentProjectionWhenOrderArrivesAfterPayment() {
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(repository.upsertPaymentIfNewer(paymentEvent("payment-prepared", "PREPARED", 1))).isEqualTo(1);
            assertThat(repository.upsertOrderIfNewer(event("order-created", "CREATED", 1, 2, 100))).isEqualTo(2);
            assertThat(repository.upsertPaymentIfNewer(paymentEvent("payment-old", "SUCCEEDED", 1))).isZero();

            jdbcTemplate.queryForObject("SELECT customer_id, payment_status, pay_no, payment_version FROM t_order_admin_read_model WHERE order_no=?",
                    (resultSet, rowNum) -> {
                        assertThat(resultSet.getString("customer_id")).isEqualTo("customer-1");
                        assertThat(resultSet.getString("payment_status")).isEqualTo("PREPARED");
                        assertThat(resultSet.getString("pay_no")).isEqualTo("P-100");
                        assertThat(resultSet.getLong("payment_version")).isEqualTo(1);
                        return null;
                    }, orderNo);
            status.setRollbackOnly();
        });
    }

    private void assertProjection(String status, long version, String eventId, int quantity, long amountCents) {
        jdbcTemplate.queryForObject("""
                SELECT status, order_version, order_event_id, quantity, amount_cents
                FROM t_order_admin_read_model
                WHERE order_no = ?
                """, (resultSet, rowNum) -> {
            assertThat(resultSet.getString("status")).isEqualTo(status);
            assertThat(resultSet.getLong("order_version")).isEqualTo(version);
            assertThat(resultSet.getString("order_event_id")).isEqualTo(eventId);
            assertThat(resultSet.getInt("quantity")).isEqualTo(quantity);
            assertThat(resultSet.getLong("amount_cents")).isEqualTo(amountCents);
            return null;
        }, orderNo);
    }

    private OrderProjectionEvent event(String eventId, String status, long version, int quantity, int amountCents) {
        return new OrderProjectionEvent(eventId, orderNo, "customer-1", "product-1", quantity, amountCents,
                status, version, Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-15T10:00:00Z"), "trace-1");
    }

    private PaymentProjectionEvent paymentEvent(String eventId, String status, long version) {
        Instant time = Instant.parse("2026-09-15T10:00:00Z");
        return new PaymentProjectionEvent(eventId, orderNo, "P-100", status, version, time, time, "trace-1");
    }

    private String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }
}
