package com.lx.governance.common.id;

import java.util.ConcurrentModificationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ID 号段数据访问层。
 * 负责从数据库获取号段，使用乐观锁防止并发冲突。
 */
@Repository
public class IdSegmentRepository {
    private static final Logger log = LoggerFactory.getLogger(IdSegmentRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public IdSegmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 分配一个新的号段。
     * 使用乐观锁更新 max_id，如果并发冲突则抛出异常由调用方重试。
     *
     * @param bizTag 业务标识
     * @return 新分配的号段
     * @throws ConcurrentModificationException 如果发生并发冲突
     * @throws IllegalStateException           如果业务标识不存在
     */
    public Segment allocateSegment(String bizTag) {
        // 1. 查询当前 max_id 和 step
        SegmentInfo info = querySegmentInfo(bizTag);
        if (info == null) {
            throw new IllegalStateException("Segment config not found for bizTag: " + bizTag);
        }

        long oldMaxId = info.getMaxId();
        int step = info.getStep();

        // 2. 使用乐观锁更新 max_id
        int rows = jdbcTemplate.update(
                "UPDATE t_id_segment SET max_id = max_id + ?, updated_at = NOW() " +
                        "WHERE biz_tag = ? AND max_id = ?",
                step, bizTag, oldMaxId);

        if (rows == 0) {
            log.warn("Segment allocation conflict for bizTag={}, oldMaxId={}", bizTag, oldMaxId);
            throw new ConcurrentModificationException(
                    "Segment allocation conflict for bizTag: " + bizTag);
        }

        // 3. 返回新号段 [oldMaxId+1, oldMaxId+step]
        long newMinId = oldMaxId + 1;
        long newMaxId = oldMaxId + step;

        log.info("Allocated segment for bizTag={}: [{}, {}]", bizTag, newMinId, newMaxId);

        return new Segment(newMinId, newMaxId, step);
    }

    /**
     * 查询号段配置信息。
     */
    private SegmentInfo querySegmentInfo(String bizTag) {
        return jdbcTemplate.query(
                "SELECT max_id, step FROM t_id_segment WHERE biz_tag = ?",
                rs -> {
                    if (rs.next()) {
                        return new SegmentInfo(rs.getLong("max_id"), rs.getInt("step"));
                    }
                    return null;
                },
                bizTag);
    }

    /**
     * 号段配置信息。
     */
    private static class SegmentInfo {
        private final long maxId;
        private final int step;

        public SegmentInfo(long maxId, int step) {
            this.maxId = maxId;
            this.step = step;
        }

        public long getMaxId() {
            return maxId;
        }

        public int getStep() {
            return step;
        }
    }
}
