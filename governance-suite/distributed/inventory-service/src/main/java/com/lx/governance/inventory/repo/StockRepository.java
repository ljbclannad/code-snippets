package com.lx.governance.inventory.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StockRepository {

    private final JdbcTemplate jdbcTemplate;

    public StockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StockRow find(String productId) {
        return jdbcTemplate.query(
                "SELECT product_id, available, version FROM t_stock WHERE product_id=?",
                (rs, n) -> new StockRow(rs.getString("product_id"), rs.getInt("available"), rs.getLong("version")),
                productId
        ).stream().findFirst().orElse(null);
    }

    public int deductWithVersion(String productId, int qty, long version) {
        return jdbcTemplate.update(
                "UPDATE t_stock SET available = available - ?, version = version + 1 WHERE product_id=? AND available >= ? AND version=?",
                qty, productId, qty, version
        );
    }

    public static class StockRow {
        public final String productId;
        public final int available;
        public final long version;

        public StockRow(String productId, int available, long version) {
            this.productId = productId;
            this.available = available;
            this.version = version;
        }
    }
}
