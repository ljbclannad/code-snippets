package com.lx.governance.outbox.repo;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepo {
    private static final String STATUS_NEW = "NEW";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_DEAD = "DEAD";

    private final JdbcTemplate jdbcTemplate;

    public OutboxRepo(@Qualifier("orderOutboxJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Record> findNew(int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, event_id, event_type, payload, retry_count
                FROM t_outbox
                WHERE status IN (?, ?)
                  AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                ORDER BY id ASC
                LIMIT ?
                """,
                (rs, n) -> new Record(rs.getLong("id"), rs.getString("event_id"), rs.getString("event_type"),
                        rs.getString("payload"), rs.getInt("retry_count")),
                STATUS_NEW, STATUS_FAILED, limit);
    }

    public int markSent(long id) {
        return jdbcTemplate.update(
                "UPDATE t_outbox SET status=?, sent_at=NOW(), error_reason=NULL, next_retry_at=NULL WHERE id=?",
                STATUS_SENT, id);
    }

    public int markFailed(long id, int nextRetryCount, int maxRetries, int retryDelaySeconds, String reason) {
        String nextStatus = nextRetryCount >= maxRetries ? STATUS_DEAD : STATUS_FAILED;
        if (STATUS_DEAD.equals(nextStatus)) {
            return jdbcTemplate.update(
                    "UPDATE t_outbox SET status=?, retry_count=?, error_reason=?, next_retry_at=NULL WHERE id=?",
                    nextStatus, nextRetryCount, reason, id);
        }
        return jdbcTemplate.update(
                """
                UPDATE t_outbox
                SET status=?,
                    retry_count=?,
                    error_reason=?,
                    next_retry_at=DATE_ADD(NOW(), INTERVAL ? SECOND)
                WHERE id=?
                """,
                nextStatus, nextRetryCount, reason, retryDelaySeconds, id);
    }

    public static class Record {
        public final long id;
        public final String eventId;
        public final String eventType;
        public final String payload;
        public final int retryCount;

        public Record(long id, String eventId, String eventType, String payload, int retryCount) {
            this.id = id;
            this.eventId = eventId;
            this.eventType = eventType;
            this.payload = payload;
            this.retryCount = retryCount;
        }
    }
}
