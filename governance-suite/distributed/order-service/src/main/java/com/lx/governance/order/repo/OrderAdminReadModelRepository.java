package com.lx.governance.order.repo;

import java.sql.Timestamp;

import com.lx.governance.order.domain.OrderProjectionEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderAdminReadModelRepository {
    private final JdbcTemplate jdbcTemplate;

    public OrderAdminReadModelRepository(@Qualifier("orderAdminReadModelJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int upsertIfNewer(OrderProjectionEvent event) {
        return jdbcTemplate.update("""
                INSERT INTO t_order_admin_read_model
                    (order_no, customer_id, product_id, quantity, amount_cents, status, order_version, event_id, occurred_at, projected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                    customer_id = IF(VALUES(order_version) > order_version, VALUES(customer_id), customer_id),
                    product_id = IF(VALUES(order_version) > order_version, VALUES(product_id), product_id),
                    quantity = IF(VALUES(order_version) > order_version, VALUES(quantity), quantity),
                    amount_cents = IF(VALUES(order_version) > order_version, VALUES(amount_cents), amount_cents),
                    status = IF(VALUES(order_version) > order_version, VALUES(status), status),
                    event_id = IF(VALUES(order_version) > order_version, VALUES(event_id), event_id),
                    occurred_at = IF(VALUES(order_version) > order_version, VALUES(occurred_at), occurred_at),
                    projected_at = IF(VALUES(order_version) > order_version, NOW(), projected_at),
                    order_version = IF(VALUES(order_version) > order_version, VALUES(order_version), order_version)
                """,
                event.orderNo(), event.customerId(), event.productId(), event.quantity(), event.amountCents(),
                event.status(), event.orderVersion(), event.eventId(), Timestamp.from(event.occurredAt()));
    }
}
