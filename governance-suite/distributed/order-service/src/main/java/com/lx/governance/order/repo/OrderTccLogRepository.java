package com.lx.governance.order.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderTccLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderTccLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TccLog findForUpdate(String orderNo) {
        return jdbcTemplate.query(
                "SELECT order_no, xid, state FROM t_order_tcc_log WHERE order_no = ? FOR UPDATE",
                (rs, n) -> new TccLog(
                        rs.getString("order_no"),
                        rs.getString("xid"),
                        rs.getString("state")),
                orderNo
        ).stream().findFirst().orElse(null);
    }

    public int insert(String orderNo, String xid, String state) {
        return jdbcTemplate.update(
                "INSERT INTO t_order_tcc_log(order_no, xid, state, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())",
                orderNo, xid, state
        );
    }

    public int updateState(String orderNo, String state) {
        return jdbcTemplate.update(
                "UPDATE t_order_tcc_log SET state = ?, updated_at = NOW() WHERE order_no = ?",
                state, orderNo
        );
    }

    public record TccLog(String orderNo, String xid, String state) {
    }
}
