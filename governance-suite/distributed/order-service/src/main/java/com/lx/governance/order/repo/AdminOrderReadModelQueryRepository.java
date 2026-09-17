package com.lx.governance.order.repo;

import java.util.ArrayList;
import java.sql.Timestamp;
import java.util.List;

import com.lx.governance.order.search.OrderOperatorOrder;
import com.lx.governance.order.search.OrderOperatorSearchCriteria;
import com.lx.governance.order.search.OrderOperatorSearchCursor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminOrderReadModelQueryRepository {
    private final JdbcTemplate jdbcTemplate;

    public AdminOrderReadModelQueryRepository(@Qualifier("orderAdminReadModelJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OrderOperatorOrder> find(OrderOperatorSearchCriteria criteria, OrderOperatorSearchCursor cursor) {
        StringBuilder sql = new StringBuilder("""
                SELECT order_no, customer_search_token, product_id, quantity, amount_cents, status, payment_status, created_at
                FROM t_order_admin_read_model WHERE order_version > 0 AND created_at IS NOT NULL
                """);
        ArrayList<Object> args = new ArrayList<>();
        appendEquals(sql, args, "order_no", criteria.orderNo());
        appendEquals(sql, args, "customer_search_token", criteria.customerSearchToken());
        appendEquals(sql, args, "product_id", criteria.productId());
        appendEquals(sql, args, "status", criteria.status());
        if (criteria.paymentStatus() != null) {
            sql.append(" AND payment_status = ?");
            args.add(criteria.paymentStatus());
        }
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
        if (cursor != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND order_no < ?))");
            Timestamp cursorTimestamp = Timestamp.from(cursor.createdAt());
            args.add(cursorTimestamp);
            args.add(cursorTimestamp);
            args.add(cursor.orderNo());
        }
        sql.append(" ORDER BY created_at DESC, order_no DESC LIMIT ?");
        args.add(criteria.limit() + 1);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new OrderOperatorOrder(rs.getString("order_no"),
                rs.getString("customer_search_token"), rs.getString("product_id"), rs.getObject("quantity", Integer.class),
                rs.getObject("amount_cents", Long.class), rs.getString("status"), rs.getString("payment_status"),
                rs.getTimestamp("created_at").toInstant()), args.toArray());
    }

    private void appendEquals(StringBuilder sql, ArrayList<Object> args, String column, String value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }
}
