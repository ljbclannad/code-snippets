package com.lx.governance.order.observability;

import java.sql.Timestamp;
import java.time.Clock;

import com.lx.governance.common.trace.Trace;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 审计只记录标识和条件指纹，不保存客户或查询原文。 */
@Component
public class AdminOrderActivity {
    private static final Logger log = LoggerFactory.getLogger(AdminOrderActivity.class);
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final Clock clock = Clock.systemUTC();

    public AdminOrderActivity(@Qualifier("orderAdminReadModelJdbcTemplate") JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    public void record(String action, Long actor, String target, String outcome, String detail) {
        insert(action, actor, target, outcome, detail);
        count(action, outcome);
    }

    public void recordBackground(String action, String target, String outcome, String detail) {
        count(action, outcome);
        try {
            insert(action, null, target, outcome, detail);
        } catch (RuntimeException exception) {
            log.error("后台订单审计写入失败 action={} target={}", action, target, exception);
        }
    }

    private void insert(String action, Long actor, String target, String outcome, String detail) {
        jdbc.update("INSERT INTO t_order_admin_activity_audit"
                + " (action, actor_user_id, target_id, outcome, detail, trace_id, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                action, actor, target, outcome, detail, Trace.getOrCreate(), Timestamp.from(clock.instant()));
    }

    private void count(String action, String outcome) {
        meters.counter("admin.order.activity.total", "action", action, "outcome", outcome).increment();
    }
}
