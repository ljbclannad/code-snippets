package com.lx.governance.vectormemory.migration;

import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import com.lx.governance.vectormemory.integration.ai.EmbeddingService;
import com.lx.governance.vectormemory.integration.storage.RedisMemoryIndexService;
import com.lx.governance.vectormemory.integration.storage.VectorStoreService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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

        // 1. 统计待迁移的记忆总数
        int totalRecords = memoryMapper.countPendingMigration();
        stats.setTotalRecords(totalRecords);

        if (totalRecords == 0) {
            log.info("没有待迁移的记忆记录");
            return stats;
        }

        log.info("待迁移记忆总数: {}", totalRecords);

        // 2. 分批处理记忆数据（使用基于游标的分页）
        long lastProcessedId = 0;
        int batchNumber = 1;
        List<Long> failedRecordIds = new ArrayList<>();

        while (true) {
            log.info("开始处理第 {} 批，lastProcessedId: {}, batchSize: {}", batchNumber, lastProcessedId, batchSize);

            // 查询当前批次的记忆数据（基于游标）
            List<AiGroupMemoryEntity> batch = memoryMapper.selectPendingMigration(lastProcessedId, batchSize);

            if (batch.isEmpty()) {
                log.info("第 {} 批查询结果为空，结束迁移", batchNumber);
                break;
            }

            // 处理当前批次
            for (AiGroupMemoryEntity memory : batch) {
                try {
                    migrateSingleMemory(memory, dryRun, stats);
                    // 更新游标为当前处理的最大ID
                    lastProcessedId = Math.max(lastProcessedId, memory.getId());
                } catch (Exception e) {
                    log.error("迁移记忆失败: id={}, groupId={}, customerId={}, error={}",
                            memory.getId(), memory.getGroupId(), memory.getCustomerId(), e.getMessage(), e);
                    stats.incrementFailed();
                    failedRecordIds.add(memory.getId());
                    // 即使失败也更新游标，避免重复处理
                    lastProcessedId = Math.max(lastProcessedId, memory.getId());
                }
            }

            // 输出当前批次进度
            log.info("第 {} 批处理完成 - 已处理: {}, 已迁移: {}, 跳过: {}, 失败: {}",
                    batchNumber, stats.getMigratedRecords() + stats.getSkippedRecords() + stats.getFailedRecords(),
                    stats.getMigratedRecords(), stats.getSkippedRecords(), stats.getFailedRecords());

            batchNumber++;
        }

        // 输出失败记录ID列表
        if (!failedRecordIds.isEmpty()) {
            log.warn("迁移失败的记录ID列表: {}", failedRecordIds);
        }

        log.info("向量记忆迁移完成 - {}", stats);
        return stats;
    }

    /**
     * 迁移单条记忆
     *
     * 注意：此方法不使用 @Transactional，因为事务边界不应跨越外部服务调用（Milvus、Redis）。
     * 只有 MySQL 更新操作需要事务保护，通过独立的 updateMySQLRecord() 方法实现。
     *
     * @param memory 记忆实体
     * @param dryRun 是否为试运行模式
     * @param stats  统计信息
     */
    protected void migrateSingleMemory(AiGroupMemoryEntity memory, boolean dryRun, MigrationStats stats) {
        Long memoryId = memory.getId();
        String content = memory.getMemoryContent();
        String groupId = memory.getGroupId();

        // 验证必要字段
        if (content == null || content.trim().isEmpty()) {
            log.warn("跳过空内容记忆: id={}", memoryId);
            stats.incrementSkipped();
            return;
        }

        if (groupId == null || groupId.trim().isEmpty()) {
            log.warn("跳过无groupId的记忆: id={}", memoryId);
            stats.incrementSkipped();
            return;
        }

        log.debug("开始迁移记忆: id={}, groupId={}, contentLength={}", memoryId, groupId, content.length());

        // 3. 生成 v3 向量
        float[] embedding = embeddingService.generateEmbedding(content);

        // 验证向量维度（EmbeddingService 已验证，此处为双重保险）
        if (embedding.length != 1024) {
            throw new IllegalStateException(
                String.format("向量维度不匹配: expected=1024, actual=%d, memoryId=%d",
                    embedding.length, memoryId)
            );
        }

        log.debug("向量生成成功: id={}, dimension={}", memoryId, embedding.length);

        if (dryRun) {
            log.info("[DRY RUN] 跳过实际写入操作: id={}", memoryId);
            stats.incrementMigrated();
            return;
        }

        // 4. 保存到 Milvus
        // TODO: 幂等性限制 - VectorStoreService 使用随机 UUID 作为 embeddingId，
        // 如果脚本在保存到 Milvus 后但更新 MySQL 前崩溃，重新运行会在 Milvus 中创建重复向量。
        // 建议改进：使用 memoryId 生成确定性的 embeddingId，或在插入前检查 Milvus 中是否已存在。
        String embeddingId = vectorStoreService.saveGroupMemory(memory);
        log.debug("Milvus保存成功: id={}, embeddingId={}", memoryId, embeddingId);

        // 5. 更新 Redis 索引
        try {
            redisMemoryIndexService.addToHashIndex(groupId, content);
            redisMemoryIndexService.cacheRecentMemory(groupId, memoryId, content, System.currentTimeMillis());
            log.debug("Redis索引更新成功: id={}, groupId={}", memoryId, groupId);
        } catch (Exception e) {
            log.warn("Redis索引更新失败（非致命错误）: id={}, error={}", memoryId, e.getMessage());
            // Redis 失败不影响迁移流程
        }

        // 6. 更新 MySQL 记录（使用独立事务）
        updateMySQLRecord(memoryId, embeddingId);
        log.debug("MySQL更新成功: id={}, embeddingId={}", memoryId, embeddingId);

        stats.incrementMigrated();
        log.info("记忆迁移成功: id={}, embeddingId={}", memoryId, embeddingId);
    }

    /**
     * 更新 MySQL 记录（独立事务方法）
     *
     * 此方法使用 @Transactional 确保 MySQL 更新的原子性。
     * 与外部服务调用（Milvus、Redis）分离，避免事务边界违规。
     *
     * @param memoryId 记忆ID
     * @param embeddingId 向量ID
     */
    @Transactional(rollbackFor = Exception.class)
    protected void updateMySQLRecord(Long memoryId, String embeddingId) {
        memoryMapper.updateEmbeddingId(memoryId, embeddingId);
    }
}
