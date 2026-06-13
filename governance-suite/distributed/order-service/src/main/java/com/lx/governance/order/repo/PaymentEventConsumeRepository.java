package com.lx.governance.order.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentEventConsumeRepository {
    private final JdbcTemplate jdbcTemplate;

    public PaymentEventConsumeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryMarkConsumed(String eventId, String eventType, String orderNo) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO t_payment_event_consume(event_id, event_type, order_no, created_at) VALUES (?,?,?,NOW())",
                    eventId, eventType, orderNo
            );
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
