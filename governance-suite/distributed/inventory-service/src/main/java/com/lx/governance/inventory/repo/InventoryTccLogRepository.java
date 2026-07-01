package com.lx.governance.inventory.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryTccLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public InventoryTccLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TccLog findForUpdate(String orderNo) {
        return jdbcTemplate.query(
                "SELECT order_no, product_id, quantity, xid, state FROM t_inventory_tcc_log WHERE order_no = ? FOR UPDATE",
                (rs, n) -> new TccLog(
                        rs.getString("order_no"),
                        rs.getString("product_id"),
                        rs.getInt("quantity"),
                        rs.getString("xid"),
                        rs.getString("state")),
                orderNo
        ).stream().findFirst().orElse(null);
    }

    public int insert(String orderNo, String productId, Integer quantity, String xid, String state) {
        return jdbcTemplate.update(
                "INSERT INTO t_inventory_tcc_log(order_no, product_id, quantity, xid, state, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
                orderNo, productId, quantity, xid, state
        );
    }

    public int updateState(String orderNo, String state) {
        return jdbcTemplate.update(
                "UPDATE t_inventory_tcc_log SET state = ?, updated_at = NOW() WHERE order_no = ?",
                state, orderNo
        );
    }

    public record TccLog(String orderNo, String productId, Integer quantity, String xid, String state) {
    }
}
