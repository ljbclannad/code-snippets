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
                "INSERT INTO t_payment(user_id, pay_no, order_no, amount_cents, status, idempotency_key, created_at) VALUES (?,?,?,?,?,?,NOW())",
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
                    p.setCreatedAt(rs.getTimestamp("created_at").toInstant());
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
                    p.setCreatedAt(rs.getTimestamp("created_at").toInstant());
                    return p;
                },
                userId, orderNo
        ).stream().findFirst().orElse(null);
    }

    public int updateStatusByOrderNo(String orderNo, String status) {
        return jdbcTemplate.update("UPDATE t_payment SET status=? WHERE order_no=?", status, orderNo);
    }

    public int updateStatusByUserIdAndOrderNo(long userId, String orderNo, String status) {
        return jdbcTemplate.update("UPDATE t_payment SET status=? WHERE user_id=? AND order_no=?", status, userId, orderNo);
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
                    p.setCreatedAt(rs.getTimestamp("created_at").toInstant());
                    return p;
                },
                userId
        );
    }
}
