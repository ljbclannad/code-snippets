package com.lx.governance.order.rebuild;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProjectionRebuildTaskRepository {
    private static final String COLUMNS = "task_id, operator_user_id, status, phase, order_watermark_0, order_watermark_1,"
            + " payment_watermark_0, payment_watermark_1, order_cursor_0, order_cursor_1, payment_cursor_0,"
            + " payment_cursor_1, processed_count, success_count, failure_count, failure_reason, created_at, updated_at";
    private final JdbcTemplate jdbc;

    public ProjectionRebuildTaskRepository(@Qualifier("orderAdminReadModelJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String id, long operator, long order0, long order1, long payment0, long payment1, Instant now) {
        jdbc.update("INSERT INTO t_order_admin_rebuild_task (task_id, operator_user_id, status, phase, order_watermark_0,"
                + " order_watermark_1, payment_watermark_0, payment_watermark_1, created_at, updated_at)"
                + " VALUES (?, ?, 'PENDING', 0, ?, ?, ?, ?, ?, ?)", id, operator, order0, order1, payment0, payment1,
                Timestamp.from(now), Timestamp.from(now));
        audit(id, operator, "CREATED", now);
    }

    public Optional<ProjectionRebuildTask> find(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM t_order_admin_rebuild_task WHERE task_id = ?",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), id);
    }

    public Optional<ProjectionRebuildTask> claim(Instant now) {
        List<String> ids = jdbc.query("SELECT task_id FROM t_order_admin_rebuild_task WHERE status = 'PENDING'"
                + " ORDER BY created_at LIMIT 1", (rs, row) -> rs.getString(1));
        if (ids.isEmpty()) return Optional.empty();
        String id = ids.get(0);
        return jdbc.update("UPDATE t_order_admin_rebuild_task SET status = 'RUNNING', updated_at = ?"
                + " WHERE task_id = ? AND status = 'PENDING'", Timestamp.from(now), id) == 1 ? find(id) : Optional.empty();
    }

    @Transactional(transactionManager = "orderAdminReadModelTransactionManager", rollbackFor = Exception.class)
    public List<String> failStale(Instant now) {
        List<String> ids = jdbc.query("SELECT task_id FROM t_order_admin_rebuild_task WHERE status = 'RUNNING'"
                + " AND updated_at < ?", (rs, row) -> rs.getString(1), Timestamp.from(now.minus(Duration.ofMinutes(5))));
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            int updated = jdbc.update("UPDATE t_order_admin_rebuild_task SET status = 'FAILED',"
                    + " failure_count = failure_count + 1, failure_reason = 'worker lease expired', updated_at = ?"
                    + " WHERE task_id = ? AND status = 'RUNNING' AND updated_at < ?", Timestamp.from(now), id,
                    Timestamp.from(now.minus(Duration.ofMinutes(5))));
            if (updated == 1) {
                audit(id, null, "FAILED", now);
                failed.add(id);
            }
        }
        return failed;
    }

    public void advance(String id, int phase, long cursor, boolean projected, Instant now) {
        String column = switch (phase) {
            case 0 -> "order_cursor_0";
            case 1 -> "order_cursor_1";
            case 2 -> "payment_cursor_0";
            case 3 -> "payment_cursor_1";
            default -> throw new IllegalArgumentException("invalid rebuild phase");
        };
        jdbc.update("UPDATE t_order_admin_rebuild_task SET " + column + " = ?, processed_count = processed_count + 1,"
                + " success_count = success_count + ?, failure_count = failure_count + ?, updated_at = ?"
                + " WHERE task_id = ? AND status = 'RUNNING'",
                cursor, projected ? 1 : 0, projected ? 0 : 1, Timestamp.from(now), id);
    }

    public void nextPhase(String id, int phase, Instant now) {
        jdbc.update("UPDATE t_order_admin_rebuild_task SET phase = ?, updated_at = ? WHERE task_id = ?"
                + " AND status = 'RUNNING' AND phase = ?", phase + 1, Timestamp.from(now), id, phase);
    }

    @Transactional(transactionManager = "orderAdminReadModelTransactionManager", rollbackFor = Exception.class)
    public void finish(String id, Instant now) {
        int updated = jdbc.update("UPDATE t_order_admin_rebuild_task SET status = 'COMPLETED', updated_at = ?"
                + " WHERE task_id = ? AND status = 'RUNNING'", Timestamp.from(now), id);
        if (updated == 1) audit(id, null, "COMPLETED", now);
    }

    @Transactional(transactionManager = "orderAdminReadModelTransactionManager", rollbackFor = Exception.class)
    public void fail(String id, String reason, Instant now) {
        int updated = jdbc.update("UPDATE t_order_admin_rebuild_task SET status = 'FAILED', failure_count = failure_count + 1,"
                + " failure_reason = ?, updated_at = ? WHERE task_id = ? AND status = 'RUNNING'",
                reason, Timestamp.from(now), id);
        if (updated == 1) audit(id, null, "FAILED", now);
    }

    public boolean retry(String id, long operator, Instant now) {
        boolean updated = jdbc.update("UPDATE t_order_admin_rebuild_task SET status = 'PENDING', failure_reason = NULL,"
                + " updated_at = ? WHERE task_id = ? AND status = 'FAILED'", Timestamp.from(now), id) == 1;
        if (updated) audit(id, operator, "RETRIED", now);
        return updated;
    }

    private void audit(String id, Long operator, String action, Instant now) {
        jdbc.update("INSERT INTO t_order_admin_rebuild_audit (task_id, operator_user_id, action, occurred_at)"
                + " VALUES (?, ?, ?, ?)", id, operator, action, Timestamp.from(now));
    }

    private ProjectionRebuildTask map(ResultSet rs) throws SQLException {
        return new ProjectionRebuildTask(rs.getString("task_id"), rs.getLong("operator_user_id"), rs.getString("status"),
                rs.getInt("phase"), rs.getLong("order_watermark_0"), rs.getLong("order_watermark_1"),
                rs.getLong("payment_watermark_0"), rs.getLong("payment_watermark_1"), rs.getLong("order_cursor_0"),
                rs.getLong("order_cursor_1"), rs.getLong("payment_cursor_0"), rs.getLong("payment_cursor_1"),
                rs.getLong("processed_count"), rs.getLong("success_count"), rs.getLong("failure_count"),
                rs.getString("failure_reason"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
