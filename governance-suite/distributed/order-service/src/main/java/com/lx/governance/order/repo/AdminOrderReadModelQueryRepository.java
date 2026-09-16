package com.lx.governance.order.repo;

import java.util.List;
import java.util.ArrayList;

import com.lx.governance.order.domain.AdminOrderReadModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminOrderReadModelQueryRepository {
    private final JdbcTemplate jdbcTemplate;

    public AdminOrderReadModelQueryRepository(@Qualifier("orderAdminReadModelJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AdminOrderReadModel> find(String paymentStatus, String payNo, Boolean paymentKnown, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT order_no, customer_id, product_id, quantity, amount_cents, status, order_version,
                       payment_status, pay_no, payment_updated_at, payment_version
                FROM t_order_admin_read_model WHERE order_version > 0
                """);
        ArrayList<Object> args = new ArrayList<>();
        if (paymentStatus != null && !paymentStatus.isBlank()) {
            sql.append(" AND payment_status = ?");
            args.add(paymentStatus);
        }
        if (payNo != null && !payNo.isBlank()) {
            sql.append(" AND pay_no = ?");
            args.add(payNo);
        }
        if (paymentKnown != null) {
            sql.append(paymentKnown ? " AND payment_status IS NOT NULL" : " AND payment_status IS NULL");
        }
        sql.append(" ORDER BY order_no ASC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new AdminOrderReadModel(rs.getString("order_no"),
                rs.getString("customer_id"), rs.getString("product_id"), rs.getObject("quantity", Integer.class),
                rs.getObject("amount_cents", Long.class), rs.getString("status"), rs.getLong("order_version"),
                rs.getString("payment_status"), rs.getString("pay_no"),
                rs.getTimestamp("payment_updated_at") == null ? null : rs.getTimestamp("payment_updated_at").toInstant(),
                rs.getLong("payment_version")), args.toArray());
    }
}
