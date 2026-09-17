package com.lx.governance.order.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.lx.governance.order.export.FinanceExportRow;
import com.lx.governance.order.export.FinanceExportStatus;
import com.lx.governance.order.export.FinanceExportTask;
import com.lx.governance.order.search.AdminOrderSort;
import com.lx.governance.order.search.AdminOrderSortDirection;
import com.lx.governance.order.search.OrderOperatorSearchCriteria;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinanceExportRepository {
    private final JdbcTemplate jdbcTemplate;

    public FinanceExportRepository(@Qualifier("orderAdminReadModelJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createAndMaterialize(String id, long ownerUserId, OrderOperatorSearchCriteria criteria, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO t_order_admin_export_task (task_id, owner_user_id, criteria_fingerprint, sort_field, sort_direction,
                  status, created_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                """, id, ownerUserId, criteria.fingerprint(), criteria.sort().name(), criteria.direction().name(), Timestamp.from(createdAt));
        StringBuilder sql = new StringBuilder("""
                INSERT INTO t_order_admin_export_row (task_id, order_no, customer_id, product_id, quantity, amount_cents, status,
                  payment_status, pay_no, payment_updated_at, created_at)
                SELECT ?, order_no, customer_id, product_id, quantity, amount_cents, status, payment_status, pay_no,
                  payment_updated_at, created_at FROM t_order_admin_read_model WHERE order_version > 0 AND created_at IS NOT NULL
                """);
        List<Object> args = new ArrayList<>();
        args.add(id);
        appendEquals(sql, args, "order_no", criteria.orderNo());
        appendEquals(sql, args, "customer_search_token", criteria.customerSearchToken());
        appendEquals(sql, args, "product_id", criteria.productId());
        appendEquals(sql, args, "status", criteria.status());
        appendEquals(sql, args, "payment_status", criteria.paymentStatus());
        sql.append(" AND created_at >= ? AND created_at <= ?");
        args.add(Timestamp.from(criteria.createdFrom()));
        args.add(Timestamp.from(criteria.createdTo()));
        if (criteria.minAmountCents() != null) {
            sql.append(" AND amount_cents >= ?");
            args.add(criteria.minAmountCents());
        }
        if (criteria.maxAmountCents() != null) {
            sql.append(" AND amount_cents <= ?");
            args.add(criteria.maxAmountCents());
        }
        if (criteria.sort() == AdminOrderSort.PAYMENT_UPDATED_AT) sql.append(" AND payment_updated_at IS NOT NULL");
        jdbcTemplate.update(sql.toString(), args.toArray());
        jdbcTemplate.update("UPDATE t_order_admin_export_task SET row_count = (SELECT COUNT(*) FROM t_order_admin_export_row WHERE task_id = ?) WHERE task_id = ?", id, id);
    }

    public Optional<FinanceExportTask> find(String id) {
        return jdbcTemplate.query("""
                SELECT task_id, owner_user_id, status, criteria_fingerprint, sort_field, sort_direction, row_count, file_key,
                  failure_summary, created_at, completed_at, expires_at FROM t_order_admin_export_task WHERE task_id = ?
                """, rs -> rs.next() ? Optional.of(task(rs)) : Optional.empty(), id);
    }

    public Optional<FinanceExportTask> claimNext(Instant now) {
        List<String> ids = jdbcTemplate.query("""
                SELECT task_id FROM t_order_admin_export_task WHERE status = 'PENDING' ORDER BY created_at LIMIT 1
                """, (rs, rowNum) -> rs.getString(1));
        if (ids.isEmpty()) return Optional.empty();
        String id = ids.get(0);
        return jdbcTemplate.update("UPDATE t_order_admin_export_task SET status = 'RUNNING', started_at = ? WHERE task_id = ? AND status = 'PENDING'",
                Timestamp.from(now), id) == 1 ? find(id) : Optional.empty();
    }

    public List<FinanceExportRow> rows(String id, FinanceExportTask task) {
        String sortColumn = switch (task.sort()) {
            case CREATED_AT -> "created_at";
            case AMOUNT_CENTS -> "amount_cents";
            case PAYMENT_UPDATED_AT -> "payment_updated_at";
        };
        String order = task.direction() == AdminOrderSortDirection.ASC ? "ASC" : "DESC";
        return jdbcTemplate.query("SELECT order_no, customer_id, product_id, quantity, amount_cents, status, payment_status, pay_no, payment_updated_at, created_at FROM t_order_admin_export_row WHERE task_id = ? ORDER BY " + sortColumn + " " + order + ", order_no " + order,
                (rs, rowNum) -> new FinanceExportRow(rs.getString("order_no"), rs.getString("customer_id"), rs.getString("product_id"),
                        rs.getObject("quantity", Integer.class), rs.getObject("amount_cents", Long.class), rs.getString("status"),
                        rs.getString("payment_status"), rs.getString("pay_no"), instant(rs.getTimestamp("payment_updated_at")),
                        instant(rs.getTimestamp("created_at"))), id);
    }

    public void markReady(String id, String fileKey, Instant completedAt, Instant expiresAt) {
        jdbcTemplate.update("UPDATE t_order_admin_export_task SET status = 'READY', file_key = ?, completed_at = ?, expires_at = ? WHERE task_id = ? AND status = 'RUNNING'", fileKey, Timestamp.from(completedAt), Timestamp.from(expiresAt), id);
    }

    public void markFailed(String id, String failureSummary) {
        jdbcTemplate.update("UPDATE t_order_admin_export_task SET status = 'FAILED', failure_summary = ? WHERE task_id = ? AND status = 'RUNNING'", failureSummary, id);
    }

    public boolean retry(String id) {
        return jdbcTemplate.update("UPDATE t_order_admin_export_task SET status = 'PENDING', failure_summary = NULL WHERE task_id = ? AND status = 'FAILED'", id) == 1;
    }

    public List<FinanceExportTask> expire(Instant now) {
        List<FinanceExportTask> tasks = jdbcTemplate.query("SELECT task_id, owner_user_id, status, criteria_fingerprint, sort_field, sort_direction, row_count, file_key, failure_summary, created_at, completed_at, expires_at FROM t_order_admin_export_task WHERE status = 'READY' AND expires_at <= ?", (rs, rowNum) -> task(rs), Timestamp.from(now));
        for (FinanceExportTask task : tasks) jdbcTemplate.update("UPDATE t_order_admin_export_task SET status = 'EXPIRED' WHERE task_id = ? AND status = 'READY' AND expires_at <= ?", task.id(), Timestamp.from(now));
        return tasks;
    }

    public void deleteRows(String id) {
        jdbcTemplate.update("DELETE FROM t_order_admin_export_row WHERE task_id = ?", id);
    }

    private FinanceExportTask task(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FinanceExportTask(rs.getString("task_id"), rs.getLong("owner_user_id"), FinanceExportStatus.valueOf(rs.getString("status")),
                rs.getString("criteria_fingerprint"), AdminOrderSort.valueOf(rs.getString("sort_field")),
                AdminOrderSortDirection.valueOf(rs.getString("sort_direction")), rs.getInt("row_count"), rs.getString("file_key"),
                rs.getString("failure_summary"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("completed_at")), instant(rs.getTimestamp("expires_at")));
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private void appendEquals(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null) { sql.append(" AND ").append(column).append(" = ?"); args.add(value); }
    }
}
