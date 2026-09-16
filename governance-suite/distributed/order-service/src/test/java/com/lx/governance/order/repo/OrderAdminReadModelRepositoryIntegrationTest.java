package com.lx.governance.order.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.lx.governance.order.domain.OrderProjectionEvent;
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
            assertThat(repository.upsertIfNewer(event("event-created", "CREATED", 1, 2, 100))).isEqualTo(1);
            assertThat(repository.upsertIfNewer(event("event-paid", "PAID", 2, 3, 150))).isEqualTo(2);
            assertThat(repository.upsertIfNewer(event("event-cancelled", "CANCELLED", 3, 3, 150))).isEqualTo(2);

            assertProjection("CANCELLED", 3, "event-cancelled", 3, 150);
            status.setRollbackOnly();
        });
    }

    @Test
    void ignoresDuplicateAndOlderOrderEvents() {
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(repository.upsertIfNewer(event("event-cancelled", "CANCELLED", 3, 3, 150))).isEqualTo(1);
            assertThat(repository.upsertIfNewer(event("event-duplicate", "PAID", 3, 88, 8888))).isZero();
            assertThat(repository.upsertIfNewer(event("event-old", "PAID", 2, 99, 9999))).isZero();

            assertProjection("CANCELLED", 3, "event-cancelled", 3, 150);
            status.setRollbackOnly();
        });
    }

    private void assertProjection(String status, long version, String eventId, int quantity, long amountCents) {
        jdbcTemplate.queryForObject("""
                SELECT status, order_version, event_id, quantity, amount_cents
                FROM t_order_admin_read_model
                WHERE order_no = ?
                """, (resultSet, rowNum) -> {
            assertThat(resultSet.getString("status")).isEqualTo(status);
            assertThat(resultSet.getLong("order_version")).isEqualTo(version);
            assertThat(resultSet.getString("event_id")).isEqualTo(eventId);
            assertThat(resultSet.getInt("quantity")).isEqualTo(quantity);
            assertThat(resultSet.getLong("amount_cents")).isEqualTo(amountCents);
            return null;
        }, orderNo);
    }

    private OrderProjectionEvent event(String eventId, String status, long version, int quantity, int amountCents) {
        return new OrderProjectionEvent(eventId, orderNo, "customer-1", "product-1", quantity, amountCents,
                status, version, Instant.parse("2026-09-15T10:00:00Z"), "trace-1");
    }

    private String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }
}
