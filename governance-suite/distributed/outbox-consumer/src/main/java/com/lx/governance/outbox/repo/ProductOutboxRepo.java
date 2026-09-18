package com.lx.governance.outbox.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/** 商品 Outbox 的待投递记录与重试状态。 */
public class ProductOutboxRepo {
    private final JdbcTemplate jdbc;

    public ProductOutboxRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OutboxRepo.Record> findNew(int limit) {
        return jdbc.query("""
                SELECT id,event_id,event_type,payload,retry_count FROM t_product_outbox
                WHERE status='NEW' AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP)
                ORDER BY id LIMIT ?
                """, (rs, row) -> new OutboxRepo.Record(rs.getLong("id"), rs.getString("event_id"),
                rs.getString("event_type"), rs.getString("payload"), rs.getInt("retry_count")), limit);
    }

    public void markSent(long id) {
        jdbc.update("UPDATE t_product_outbox SET status='SENT',sent_at=CURRENT_TIMESTAMP,error_reason=NULL,next_retry_at=NULL WHERE id=? AND status='NEW'", id);
    }

    public void markFailed(long id, int retryCount, int maxRetries, int delaySeconds, String reason) {
        boolean dead = retryCount >= maxRetries;
        jdbc.update("UPDATE t_product_outbox SET status=?,retry_count=?,error_reason=?,next_retry_at=? WHERE id=? AND status='NEW'",
                dead ? "DEAD" : "NEW", retryCount, reason,
                dead ? null : Timestamp.from(Instant.now().plusSeconds(delaySeconds)), id);
    }
}
