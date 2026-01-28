package com.lx.governance.order.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(String eventId, String aggregateType, String aggregateId, String eventType, String payload) {
        return jdbcTemplate.update(
                "INSERT INTO t_outbox(event_id, aggregate_type, aggregate_id, event_type, payload, status, created_at) VALUES (?,?,?,?,?,'NEW',NOW())",
                eventId, aggregateType, aggregateId, eventType, payload
        );
    }
}
