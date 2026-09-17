package com.lx.governance.order.rebuild;

import java.util.List;

import com.lx.governance.order.domain.OrderProjectionEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRebuildFactRepository {
    private final JdbcTemplate jdbc;

    public OrderRebuildFactRepository(@Qualifier("orderAdminReadModelJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long watermark(int shard) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table(shard), Long.class);
        return value == null ? 0 : value;
    }

    public List<OrderFact> page(int shard, long afterId, long throughId, int limit) {
        return jdbc.query("SELECT id, order_no, customer_id, product_id, quantity, amount_cents, status, version,"
                + " created_at, updated_at FROM " + table(shard) + " WHERE id > ? AND id <= ? ORDER BY id LIMIT ?",
                (rs, row) -> new OrderFact(rs.getLong("id"), new OrderProjectionEvent(
                        "rebuild-order-" + rs.getLong("id"), rs.getString("order_no"), rs.getString("customer_id"),
                        rs.getString("product_id"), rs.getInt("quantity"), rs.getInt("amount_cents"),
                        rs.getString("status"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), "projection-rebuild")), afterId, throughId, limit);
    }

    private String table(int shard) {
        if (shard != 0 && shard != 1) throw new IllegalArgumentException("invalid order shard");
        return "t_order_" + shard;
    }

    public record OrderFact(long id, OrderProjectionEvent event) { }
}
