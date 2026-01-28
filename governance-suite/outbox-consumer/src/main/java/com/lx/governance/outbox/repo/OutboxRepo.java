package com.lx.governance.outbox.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class OutboxRepo {

    private final JdbcTemplate jdbcTemplate;

    public OutboxRepo(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Record> findNew(int limit) {
        return jdbcTemplate.query(
                "SELECT id, event_id, event_type, payload FROM t_outbox WHERE status='NEW' ORDER BY id ASC LIMIT ?",
                (rs, n) -> new Record(rs.getLong("id"), rs.getString("event_id"), rs.getString("event_type"), rs.getString("payload")),
                limit
        );
    }

    public int markSent(long id) {
        return jdbcTemplate.update("UPDATE t_outbox SET status='SENT', sent_at=NOW() WHERE id=?", id);
    }

    public int markFailed(long id, String reason) {
        return jdbcTemplate.update("UPDATE t_outbox SET status='FAILED', error_reason=? WHERE id=?", reason, id);
    }

    public static class Record {
        public final long id;
        public final String eventId;
        public final String eventType;
        public final String payload;

        public Record(long id, String eventId, String eventType, String payload) {
            this.id = id;
            this.eventId = eventId;
            this.eventType = eventType;
            this.payload = payload;
        }
    }
}
