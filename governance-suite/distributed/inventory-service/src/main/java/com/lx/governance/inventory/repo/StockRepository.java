package com.lx.governance.inventory.repo;

import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;
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

    public List<StockRow> findByProductIds(Collection<String> productIds) {
        if (productIds.isEmpty()) return List.of();
        StringJoiner placeholders = new StringJoiner(", ");
        productIds.forEach(ignored -> placeholders.add("?"));
        return jdbcTemplate.query("SELECT product_id, available, version FROM t_stock WHERE product_id IN (" + placeholders + ")",
                (rs, n) -> new StockRow(rs.getString("product_id"), rs.getInt("available"), rs.getLong("version")),
                productIds.toArray());
    }

    public int deductWithVersion(String productId, int qty, long version) {
        return jdbcTemplate.update(
                "UPDATE t_stock SET available = available - ?, version = version + 1 WHERE product_id=? AND available >= ? AND version=?",
                qty, productId, qty, version
        );
    }

    public int increase(String productId, int qty) {
        return jdbcTemplate.update(
                "UPDATE t_stock SET available = available + ?, version = version + 1 WHERE product_id=?",
                qty, productId
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
