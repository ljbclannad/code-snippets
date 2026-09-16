package com.lx.governance.payment.repo;

import com.lx.governance.payment.domain.PaymentDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
    private final JdbcTemplate jdbcTemplate;

    public PaymentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(long userId, String payNo, String orderNo, int amountCents, String status, String idempotencyKey) {
        return jdbcTemplate.update(
            "INSERT INTO t_payment(user_id, pay_no, order_no, amount_cents, status, idempotency_key, version, created_at) VALUES (?,?,?,?,?,?,1,NOW())",
                userId, payNo, orderNo, amountCents, status, idempotencyKey
        );
    }

    public PaymentDto findByOrderNo(String orderNo) {
        return jdbcTemplate.query(
                "SELECT * FROM t_payment WHERE order_no=? ORDER BY id DESC LIMIT 1",
                (rs, n) -> {
                    PaymentDto p = new PaymentDto();
                    p.setId(rs.getLong("id"));
                    p.setUserId(rs.getLong("user_id"));
                    p.setPayNo(rs.getString("pay_no"));
                    p.setOrderNo(rs.getString("order_no"));
                    p.setAmountCents(rs.getInt("amount_cents"));
                    p.setStatus(rs.getString("status"));
                    populateTimestampsAndVersion(rs, p);
                    return p;
                },
                orderNo
        ).stream().findFirst().orElse(null);
    }

    public PaymentDto findByUserIdAndOrderNo(long userId, String orderNo) {
        return jdbcTemplate.query(
                "SELECT * FROM t_payment WHERE user_id=? AND order_no=? ORDER BY id DESC LIMIT 1",
                (rs, n) -> {
                    PaymentDto p = new PaymentDto();
                    p.setId(rs.getLong("id"));
                    p.setUserId(rs.getLong("user_id"));
                    p.setPayNo(rs.getString("pay_no"));
                    p.setOrderNo(rs.getString("order_no"));
                    p.setAmountCents(rs.getInt("amount_cents"));
                    p.setStatus(rs.getString("status"));
                    populateTimestampsAndVersion(rs, p);
                    return p;
                },
                userId, orderNo
        ).stream().findFirst().orElse(null);
    }

    public int updateStatusByOrderNo(String orderNo, String status) {
        return jdbcTemplate.update("UPDATE t_payment SET status=?, version=version+1 WHERE order_no=? AND status<>?", status, orderNo, status);
    }

    public int updateStatusByUserIdAndOrderNo(long userId, String orderNo, String status) {
        return jdbcTemplate.update("UPDATE t_payment SET status=?, version=version+1 WHERE user_id=? AND order_no=? AND status<>?", status, userId, orderNo, status);
    }

    public java.util.List<PaymentDto> findByUserId(long userId) {
        return jdbcTemplate.query(
                "SELECT * FROM t_payment WHERE user_id=? ORDER BY id DESC",
                (rs, n) -> {
                    PaymentDto p = new PaymentDto();
                    p.setId(rs.getLong("id"));
                    p.setUserId(rs.getLong("user_id"));
                    p.setPayNo(rs.getString("pay_no"));
                    p.setOrderNo(rs.getString("order_no"));
                    p.setAmountCents(rs.getInt("amount_cents"));
                    p.setStatus(rs.getString("status"));
                    populateTimestampsAndVersion(rs, p);
                    return p;
                },
                userId
        );
    }

    private void populateTimestampsAndVersion(java.sql.ResultSet rs, PaymentDto payment) throws java.sql.SQLException {
        payment.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        payment.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        payment.setVersion(rs.getLong("version"));
    }
}
