package com.example.idgendemo.id;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

public class SegmentDao {

    private final JdbcTemplate jdbcTemplate;
    private final IdGenMetrics metrics;

    public SegmentDao(JdbcTemplate jdbcTemplate, IdGenMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
    }

    /**
     * 从 DB 分配一个号段：对同一个 bizTag 行锁串行，避免重复。
     */
    @Transactional
    public Segment allocate(String bizTag) {
        long t0 = System.nanoTime();
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT max_id, step, version FROM id_alloc WHERE biz_tag = ? FOR UPDATE", bizTag);

            long maxId = ((Number) row.get("max_id")).longValue();
            int step = ((Number) row.get("step")).intValue();
            long version = ((Number) row.get("version")).longValue();

            long newMax = maxId + step;

            int updated = jdbcTemplate.update(
                    "UPDATE id_alloc SET max_id = ?, version = ?, update_time = NOW() WHERE biz_tag = ? AND version = ?",
                    newMax, version + 1, bizTag, version);

            if (updated != 1) {
                throw new IllegalStateException("Optimistic update failed for bizTag=" + bizTag);
            }

            metrics.segmentAllocate.increment();
            return new Segment(maxId + 1, newMax);
        } finally {
            long dt = System.nanoTime() - t0;
            metrics.recordSegmentAllocateNanos(dt);
        }
    }
}
