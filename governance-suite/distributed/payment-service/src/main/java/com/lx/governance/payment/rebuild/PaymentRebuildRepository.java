package com.lx.governance.payment.rebuild;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRebuildRepository {
    private final JdbcTemplate jdbc;

    public PaymentRebuildRepository(@Qualifier("paymentRebuildJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PaymentRebuildFacts.Watermark watermark() {
        return new PaymentRebuildFacts.Watermark(maxId(0), maxId(1));
    }

    public List<PaymentRebuildFacts.Fact> page(int shard, long afterId, long throughId, int limit) {
        String table = table(shard);
        return jdbc.query("SELECT id, order_no, pay_no, status, version, updated_at FROM " + table
                + " WHERE id > ? AND id <= ? ORDER BY id LIMIT ?", (rs, row) ->
                new PaymentRebuildFacts.Fact(rs.getLong("id"), rs.getString("order_no"), rs.getString("pay_no"),
                        rs.getString("status"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant()),
                afterId, throughId, limit);
    }

    private long maxId(int shard) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table(shard), Long.class);
        return value == null ? 0 : value;
    }

    private String table(int shard) {
        if (shard != 0 && shard != 1) throw new IllegalArgumentException("invalid payment shard");
        return "t_payment_" + shard;
    }
}
