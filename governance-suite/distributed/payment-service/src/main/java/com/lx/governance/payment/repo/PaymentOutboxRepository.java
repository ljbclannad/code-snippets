package com.lx.governance.payment.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentOutboxRepository {
    private final JdbcTemplate jdbcTemplate;

    public PaymentOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(String eventId, String aggregateId, String eventType, String payload) {
        return jdbcTemplate.update("""
                INSERT INTO t_outbox (event_id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'PAYMENT', ?, ?, ?)
                """, eventId, aggregateId, eventType, payload);
    }
}
