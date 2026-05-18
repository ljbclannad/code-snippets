package com.lx.governance.vectormemory.migration;

import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import com.lx.governance.vectormemory.service.EmbeddingService;
import com.lx.governance.vectormemory.service.RedisMemoryIndexService;
import com.lx.governance.vectormemory.service.VectorStoreService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 向量记忆迁移脚本
 * 用于将历史记忆数据迁移到新的向量存储系统
 *
 * @author LeJinBo
 * @since 2026-05-18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorMemoryMigrationScript {

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final RedisMemoryIndexService redisMemoryIndexService;
    private final AiGroupMemoryMapper memoryMapper;

    /**
     * 迁移统计数据结构
     *
     * 注意：此类设计为单线程使用场景，不提供线程安全保证。
     * 在并发环境中使用需要外部同步。
     */
    @Data
    public static class MigrationStats {
        private int totalRecords;
        private int migratedRecords;
        private int skippedRecords;
        private int failedRecords;

        /**
         * 增加已迁移记录数
         */
        public void incrementMigrated() {
            migratedRecords++;
        }

        /**
         * 增加跳过记录数
         */
        public void incrementSkipped() {
            skippedRecords++;
        }

        /**
         * 增加失败记录数
         */
        public void incrementFailed() {
            failedRecords++;
        }

        @Override
        public String toString() {
            return String.format("MigrationStats{total=%d, migrated=%d, skipped=%d, failed=%d}",
                    totalRecords, migratedRecords, skippedRecords, failedRecords);
        }
    }

    /**
     * 执行记忆迁移
     *
     * @param batchSize 批次大小（必须大于0）
     * @param dryRun    是否为试运行模式（不实际写入数据）
     * @return 迁移统计信息
     * @throws IllegalArgumentException 如果 batchSize <= 0
     */
    public MigrationStats migrateMemories(int batchSize, boolean dryRun) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive, got: " + batchSize);
        }

        log.info("开始向量记忆迁移 - batchSize: {}, dryRun: {}", batchSize, dryRun);

        MigrationStats stats = new MigrationStats();

        // TODO: 在 Task 7 中实现核心迁移逻辑
        // 1. 从 MySQL 查询待迁移的记忆数据（使用 memoryMapper 分页查询 embedding_id 为 NULL 的记录）
        // 2. 分批处理记忆数据（每批 batchSize 条记录）
        // 3. 为每条记忆生成向量（调用 embeddingService.generateEmbedding）
        // 4. 保存到 Milvus 向量存储（调用 vectorStoreService.upsert）
        // 5. 更新 Redis 索引（调用 redisMemoryIndexService.indexMemory）
        // 6. 更新 MySQL 记录状态（调用 memoryMapper.updateEmbeddingId，需要 @Transactional）
        // 7. 错误处理：单条记录失败不影响整批，记录到 failedRecords，继续处理下一条
        // 8. 回滚策略：dryRun=true 时跳过所有写操作，仅统计
        // 9. 进度日志：每处理完一批记录后输出当前进度（已处理/总数）

        log.info("向量记忆迁移完成 - {}", stats);
        return stats;
    }
}
