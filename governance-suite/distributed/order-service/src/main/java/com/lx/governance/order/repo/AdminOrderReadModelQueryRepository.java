package com.lx.governance.order.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.lx.governance.order.search.AdminOrderSort;
import com.lx.governance.order.search.AdminOrderSortDirection;
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
        String sortColumn = criteria.sort().column();
        String comparison = criteria.direction() == AdminOrderSortDirection.ASC ? ">" : "<";
        String order = criteria.direction().name();
        StringBuilder sql = new StringBuilder("""
                SELECT order_no, customer_id, customer_search_token, product_id, quantity, amount_cents, status,
                       payment_status, pay_no, payment_updated_at, created_at
                FROM t_order_admin_read_model WHERE order_version > 0 AND created_at IS NOT NULL
                """);
        ArrayList<Object> args = new ArrayList<>();
        appendEquals(sql, args, "order_no", criteria.orderNo());
        appendEquals(sql, args, "customer_search_token", criteria.customerSearchToken());
        appendEquals(sql, args, "product_id", criteria.productId());
        appendEquals(sql, args, "status", criteria.status());
        appendEquals(sql, args, "payment_status", criteria.paymentStatus());
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
        if (criteria.sort() == AdminOrderSort.PAYMENT_UPDATED_AT) sql.append(" AND payment_updated_at IS NOT NULL");
        if (cursor != null) {
            Object sortValue = cursorValue(criteria.sort(), cursor.sortValue());
            sql.append(" AND (").append(sortColumn).append(' ').append(comparison).append(" ? OR (")
                    .append(sortColumn).append(" = ? AND order_no ").append(comparison).append(" ?))");
            args.add(sortValue);
            args.add(sortValue);
            args.add(cursor.orderNo());
        }
        sql.append(" ORDER BY ").append(sortColumn).append(' ').append(order).append(", order_no ").append(order)
                .append(" LIMIT ?");
        args.add(criteria.limit() + 1);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new OrderOperatorOrder(rs.getString("order_no"),
                rs.getString("customer_id"), rs.getString("customer_search_token"), rs.getString("product_id"),
                rs.getObject("quantity", Integer.class), rs.getObject("amount_cents", Long.class), rs.getString("status"),
                rs.getString("payment_status"), rs.getString("pay_no"), instantOrNull(rs.getTimestamp("payment_updated_at")),
                rs.getTimestamp("created_at").toInstant()), args.toArray());
    }

    private Object cursorValue(AdminOrderSort sort, String value) {
        return switch (sort) {
            case CREATED_AT, PAYMENT_UPDATED_AT -> Timestamp.from(Instant.parse(value));
            case AMOUNT_CENTS -> Long.parseLong(value);
        };
    }

    private Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void appendEquals(StringBuilder sql, ArrayList<Object> args, String column, String value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }
}
