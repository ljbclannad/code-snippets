package com.lx.governance.order.repo;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.lx.governance.order.domain.OrderProjectionEvent;
import com.lx.governance.order.domain.PaymentProjectionEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderAdminReadModelRepository {
    private final JdbcTemplate jdbcTemplate;
    private final String customerSearchTokenSecret;

    @Autowired
    public OrderAdminReadModelRepository(@Qualifier("orderAdminReadModelJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Value("${app.admin-search.customer-token-secret:${app.auth.token-secret:governance-suite-dev-secret}}") String customerSearchTokenSecret) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerSearchTokenSecret = customerSearchTokenSecret;
    }

    public OrderAdminReadModelRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, "governance-suite-dev-secret");
    }

    public int upsertOrderIfNewer(OrderProjectionEvent event) {
        return jdbcTemplate.update("""
                INSERT INTO t_order_admin_read_model
                    (order_no, customer_id, customer_search_token, product_id, quantity, amount_cents, status, order_version, order_event_id, created_at, occurred_at, projected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                    customer_id = IF(VALUES(order_version) > order_version, VALUES(customer_id), customer_id),
                    customer_search_token = IF(VALUES(order_version) > order_version, VALUES(customer_search_token), customer_search_token),
                    product_id = IF(VALUES(order_version) > order_version, VALUES(product_id), product_id),
                    quantity = IF(VALUES(order_version) > order_version, VALUES(quantity), quantity),
                    amount_cents = IF(VALUES(order_version) > order_version, VALUES(amount_cents), amount_cents),
                    status = IF(VALUES(order_version) > order_version, VALUES(status), status),
                    order_event_id = IF(VALUES(order_version) > order_version, VALUES(order_event_id), order_event_id),
                    created_at = IF(created_at IS NULL, VALUES(created_at), created_at),
                    occurred_at = IF(VALUES(order_version) > order_version, VALUES(occurred_at), occurred_at),
                    projected_at = IF(VALUES(order_version) > order_version, NOW(), projected_at),
                    order_version = IF(VALUES(order_version) > order_version, VALUES(order_version), order_version)
                """,
                event.orderNo(), event.customerId(), customerSearchToken(event.customerId()), event.productId(), event.quantity(),
                event.amountCents(), event.status(), event.orderVersion(), event.eventId(), Timestamp.from(event.createdAt()),
                Timestamp.from(event.occurredAt()));
    }

    public int upsertPaymentIfNewer(PaymentProjectionEvent event) {
        return jdbcTemplate.update("""
                INSERT INTO t_order_admin_read_model
                    (order_no, payment_status, pay_no, payment_updated_at, payment_version, payment_event_id, payment_occurred_at, projected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                    payment_status = IF(VALUES(payment_version) > payment_version, VALUES(payment_status), payment_status),
                    pay_no = IF(VALUES(payment_version) > payment_version, VALUES(pay_no), pay_no),
                    payment_updated_at = IF(VALUES(payment_version) > payment_version, VALUES(payment_updated_at), payment_updated_at),
                    payment_event_id = IF(VALUES(payment_version) > payment_version, VALUES(payment_event_id), payment_event_id),
                    payment_occurred_at = IF(VALUES(payment_version) > payment_version, VALUES(payment_occurred_at), payment_occurred_at),
                    projected_at = IF(VALUES(payment_version) > payment_version, NOW(), projected_at),
                    payment_version = IF(VALUES(payment_version) > payment_version, VALUES(payment_version), payment_version)
                """, event.orderNo(), event.paymentStatus(), event.payNo(), Timestamp.from(event.paymentUpdatedAt()),
                event.paymentVersion(), event.eventId(), Timestamp.from(event.occurredAt()));
    }

    private String customerSearchToken(String customerId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(customerSearchTokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(customerId.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create customer search token", exception);
        }
    }
}
