package com.lx.governance.order.rebuild;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ProjectionRebuildTaskRepositoryIntegrationTest {
    @Test
    void retriesFailedTaskWithOriginalWatermarksAndAudit() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl("jdbc:mysql://%s:%s/order_db?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                .formatted(env("MYSQL_HOST", "127.0.0.1"), env("MYSQL_PORT", "3306")));
        dataSource.setUsername(env("MYSQL_USER", "root"));
        dataSource.setPassword(env("MYSQL_PASS", "root"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ProjectionRebuildTaskRepository repository = new ProjectionRebuildTaskRepository(jdbc);
        String id = UUID.randomUUID().toString();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            Instant now = Instant.parse("2026-09-17T10:00:00Z");
            repository.create(id, 9, 11, 22, 31, 42, now);
            jdbc.update("UPDATE t_order_admin_rebuild_task SET status = 'FAILED', phase = 2, order_cursor_0 = 7,"
                    + " processed_count = 7, failure_count = 1, failure_reason = 'temporary failure' WHERE task_id = ?", id);

            assertThat(repository.retry(id, 10, now.plusSeconds(1))).isTrue();
            ProjectionRebuildTask task = repository.find(id).orElseThrow();
            assertThat(task.status()).isEqualTo("PENDING");
            assertThat(task.phase()).isEqualTo(2);
            assertThat(task.orderCursor0()).isEqualTo(7);
            assertThat(task.orderWatermark0()).isEqualTo(11);
            assertThat(task.orderWatermark1()).isEqualTo(22);
            assertThat(task.paymentWatermark0()).isEqualTo(31);
            assertThat(task.paymentWatermark1()).isEqualTo(42);
            assertThat(task.processedCount()).isEqualTo(7);
            assertThat(task.failureCount()).isEqualTo(1);
            assertThat(task.failureReason()).isNull();
            assertThat(repository.retry(id, 10, now.plusSeconds(2))).isFalse();
            assertThat(jdbc.queryForList("SELECT action FROM t_order_admin_rebuild_audit WHERE task_id = ? ORDER BY id",
                    String.class, id)).containsExactly("CREATED", "RETRIED");
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_order_admin_rebuild_task WHERE task_id = ?",
                Integer.class, id)).isZero();
    }

    private String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }
}
