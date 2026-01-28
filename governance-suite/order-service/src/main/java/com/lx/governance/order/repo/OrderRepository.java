package com.lx.governance.order.repo;

import com.lx.governance.order.domain.OrderDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

@Repository
public class OrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<OrderDto> MAPPER = (rs, rowNum) -> {
        OrderDto o = new OrderDto();
        o.setId(rs.getLong("id"));
        o.setOrderNo(rs.getString("order_no"));
        o.setCustomerId(rs.getString("customer_id"));
        o.setProductId(rs.getString("product_id"));
        o.setQuantity(rs.getInt("quantity"));
        o.setAmountCents(rs.getInt("amount_cents"));
        o.setStatus(rs.getString("status"));
        o.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return o;
    };

    public int insert(String orderNo, String customerId, String productId, int quantity, int amountCents, String status) {
        return jdbcTemplate.update(
                "INSERT INTO t_order(order_no, customer_id, product_id, quantity, amount_cents, status, created_at) VALUES (?,?,?,?,?,?,NOW())",
                orderNo, customerId, productId, quantity, amountCents, status
        );
    }

    public OrderDto findByOrderNo(String orderNo) {
        return jdbcTemplate.query("SELECT * FROM t_order WHERE order_no=?", MAPPER, orderNo)
                .stream().findFirst().orElse(null);
    }

    public int updateStatus(String orderNo, String status) {
        return jdbcTemplate.update("UPDATE t_order SET status=? WHERE order_no=?", status, orderNo);
    }
}
