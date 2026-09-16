package com.lx.governance.outbox.repo;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentOutboxRepo {
    private final JdbcTemplate jdbcTemplate;

    public PaymentOutboxRepo(@Qualifier("paymentOutboxJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OutboxRepo.Record> findNew(int limit) {
        return jdbcTemplate.query("""
                SELECT id, event_id, event_type, payload, retry_count
                FROM t_outbox
                WHERE status IN ('NEW', 'FAILED')
                  AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                ORDER BY id ASC LIMIT ?
                """, (rs, n) -> new OutboxRepo.Record(rs.getLong("id"), rs.getString("event_id"),
                rs.getString("event_type"), rs.getString("payload"), rs.getInt("retry_count")), limit);
    }

    public int markSent(long id) {
        return jdbcTemplate.update("UPDATE t_outbox SET status='SENT', sent_at=NOW(), error_reason=NULL, next_retry_at=NULL WHERE id=?", id);
    }

    public int markFailed(long id, int retryCount, int maxRetries, int delaySeconds, String reason) {
        String status = retryCount >= maxRetries ? "DEAD" : "FAILED";
        return jdbcTemplate.update("""
                UPDATE t_outbox SET status=?, retry_count=?, error_reason=?,
                next_retry_at=IF(? = 'DEAD', NULL, DATE_ADD(NOW(), INTERVAL ? SECOND)) WHERE id=?
                """, status, retryCount, reason, status, delaySeconds, id);
    }
}
