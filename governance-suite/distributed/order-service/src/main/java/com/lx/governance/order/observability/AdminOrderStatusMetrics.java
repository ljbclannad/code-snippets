package com.lx.governance.order.observability;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdminOrderStatusMetrics {
    private final JdbcTemplate jdbc;
    private final Map<String, AtomicInteger> exportCounts;
    private final Map<String, AtomicInteger> rebuildCounts;

    public AdminOrderStatusMetrics(@Qualifier("orderAdminReadModelJdbcTemplate") JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        exportCounts = gauges(meters, "admin.order.export.tasks", "PENDING", "RUNNING", "READY", "FAILED", "EXPIRED");
        rebuildCounts = gauges(meters, "admin.order.rebuild.tasks", "PENDING", "RUNNING", "COMPLETED", "FAILED");
    }

    @Scheduled(fixedDelayString = "${app.admin-observability.status-poll-ms:30000}")
    public void refresh() {
        refresh("t_order_admin_export_task", exportCounts);
        refresh("t_order_admin_rebuild_task", rebuildCounts);
    }

    private Map<String, AtomicInteger> gauges(MeterRegistry meters, String name, String... statuses) {
        Map<String, AtomicInteger> values = new LinkedHashMap<>();
        for (String status : statuses) {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder(name, value, AtomicInteger::get).tag("status", status).register(meters);
            values.put(status, value);
        }
        return values;
    }

    private void refresh(String table, Map<String, AtomicInteger> counts) {
        Map<String, Integer> latest = new HashMap<>();
        jdbc.query("SELECT status, COUNT(*) AS count FROM " + table + " GROUP BY status", rs -> {
            latest.put(rs.getString("status"), rs.getInt("count"));
        });
        counts.forEach((status, value) -> value.set(latest.getOrDefault(status, 0)));
    }
}
