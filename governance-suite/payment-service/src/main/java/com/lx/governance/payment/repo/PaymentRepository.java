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

    public int insert(String payNo, String orderNo, int amountCents, String status) {
        return jdbcTemplate.update(
                "INSERT INTO t_payment(pay_no, order_no, amount_cents, status, created_at) VALUES (?,?,?,?,NOW())",
                payNo, orderNo, amountCents, status
        );
    }

    public PaymentDto findByOrderNo(String orderNo) {
        return jdbcTemplate.query(
                "SELECT * FROM t_payment WHERE order_no=? ORDER BY id DESC LIMIT 1",
                (rs, n) -> {
                    PaymentDto p = new PaymentDto();
                    p.setId(rs.getLong("id"));
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

    public int updateStatusByOrderNo(String orderNo, String status) {
        return jdbcTemplate.update("UPDATE t_payment SET status=? WHERE order_no=?", status, orderNo);
    }
}
