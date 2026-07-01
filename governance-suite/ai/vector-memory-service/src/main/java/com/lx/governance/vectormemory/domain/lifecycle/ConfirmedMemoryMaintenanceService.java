package com.lx.governance.vectormemory.domain.lifecycle;

import com.lx.governance.vectormemory.integration.storage.VectorStoreService;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 正式长期记忆维护服务
 *
 * 用于处理“正式记忆内容已修改，需要同步刷新 Milvus 向量”的场景。
 * 当前策略：
 * 1. 先新增新向量，避免先删旧向量导致检索短暂丢失
 * 2. 再更新 MySQL，使 embedding_id 指向新向量
 * 3. 最后尽力删除旧向量
 *
 * 如果后续需要更强一致性，可在这里继续接入 MySQL/Milvus 补偿任务表或重试队列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmedMemoryMaintenanceService {

    private final AiGroupMemoryMapper confirmedMemoryMapper;
    private final VectorStoreService vectorStoreService;

    /**
     * 刷新正式记忆内容及其向量。
     *
     * @param updatedMemory 包含正式记忆最新字段的实体，必须带 id
     * @return 新的 embeddingId
     */
    @Transactional
    public String refreshConfirmedMemory(AiGroupMemoryEntity updatedMemory) {
        if (updatedMemory == null || updatedMemory.getId() == null) {
            throw new IllegalArgumentException("Updated memory and id are required");
        }

        AiGroupMemoryEntity existing = confirmedMemoryMapper.selectById(updatedMemory.getId());
        if (existing == null) {
            throw new IllegalArgumentException("Confirmed memory not found: " + updatedMemory.getId());
        }

        String oldEmbeddingId = existing.getEmbeddingId();
        String newEmbeddingId = UUID.randomUUID().toString();

        mergeMissingFields(updatedMemory, existing);

        try {
            // 先写入新向量，成功后再切换 MySQL 指针。
            vectorStoreService.saveGroupMemory(updatedMemory, newEmbeddingId);

            updatedMemory.setEmbeddingId(newEmbeddingId);
            confirmedMemoryMapper.updateConfirmedMemory(updatedMemory);

            cleanupOldEmbeddingQuietly(oldEmbeddingId, newEmbeddingId, updatedMemory.getId());
            log.info("Confirmed memory refreshed successfully: memoryId={}, oldEmbeddingId={}, newEmbeddingId={}",
                    updatedMemory.getId(), oldEmbeddingId, newEmbeddingId);
            return newEmbeddingId;
        } catch (Exception e) {
            // 基础补偿：如果 MySQL 更新失败或后续逻辑失败，尽力删除刚写入的新向量，避免 Milvus 残留孤儿数据。
            cleanupNewEmbeddingOnFailure(newEmbeddingId, updatedMemory.getId(), e);
            throw e;
        }
    }

    private void mergeMissingFields(AiGroupMemoryEntity updatedMemory, AiGroupMemoryEntity existing) {
        if (updatedMemory.getCustomerId() == null) {
            updatedMemory.setCustomerId(existing.getCustomerId());
        }
        if (updatedMemory.getGroupId() == null) {
            updatedMemory.setGroupId(existing.getGroupId());
        }
        if (updatedMemory.getMemoryType() == null) {
            updatedMemory.setMemoryType(existing.getMemoryType());
        }
        if (updatedMemory.getMemoryKey() == null) {
            updatedMemory.setMemoryKey(existing.getMemoryKey());
        }
        if (updatedMemory.getMemoryContent() == null) {
            updatedMemory.setMemoryContent(existing.getMemoryContent());
        }
        if (updatedMemory.getMemoryRepresentation() == null) {
            updatedMemory.setMemoryRepresentation(existing.getMemoryRepresentation());
        }
        if (updatedMemory.getSummaryGroupKey() == null) {
            updatedMemory.setSummaryGroupKey(existing.getSummaryGroupKey());
        }
        if (updatedMemory.getSummaryOfMemoryId() == null) {
            updatedMemory.setSummaryOfMemoryId(existing.getSummaryOfMemoryId());
        }
        if (updatedMemory.getLifecycleStatus() == null) {
            updatedMemory.setLifecycleStatus(existing.getLifecycleStatus());
        }
        if (updatedMemory.getRetentionPolicy() == null) {
            updatedMemory.setRetentionPolicy(existing.getRetentionPolicy());
        }
        if (updatedMemory.getExpiresAt() == null) {
            updatedMemory.setExpiresAt(existing.getExpiresAt());
        }
        if (updatedMemory.getDecayScore() == null) {
            updatedMemory.setDecayScore(existing.getDecayScore());
        }
        if (updatedMemory.getLastAccessedAt() == null) {
            updatedMemory.setLastAccessedAt(existing.getLastAccessedAt());
        }
        if (updatedMemory.getLastReinforcedAt() == null) {
            updatedMemory.setLastReinforcedAt(existing.getLastReinforcedAt());
        }
        if (updatedMemory.getSourceCount() == null) {
            updatedMemory.setSourceCount(existing.getSourceCount());
        }
        if (updatedMemory.getEvidenceCount() == null) {
            updatedMemory.setEvidenceCount(existing.getEvidenceCount());
        }
        if (updatedMemory.getConflictStatus() == null) {
            updatedMemory.setConflictStatus(existing.getConflictStatus());
        }
        if (updatedMemory.getSupersededBy() == null) {
            updatedMemory.setSupersededBy(existing.getSupersededBy());
        }
        if (updatedMemory.getArchivedAt() == null) {
            updatedMemory.setArchivedAt(existing.getArchivedAt());
        }
        if (updatedMemory.getArchiveReason() == null) {
            updatedMemory.setArchiveReason(existing.getArchiveReason());
        }
        if (updatedMemory.getDecisionReasonCode() == null) {
            updatedMemory.setDecisionReasonCode(existing.getDecisionReasonCode());
        }
        if (updatedMemory.getConfidenceScore() == null) {
            updatedMemory.setConfidenceScore(existing.getConfidenceScore());
        }
        if (updatedMemory.getSourceMessage() == null) {
            updatedMemory.setSourceMessage(existing.getSourceMessage());
        }
        if (updatedMemory.getExtractedAt() == null) {
            updatedMemory.setExtractedAt(existing.getExtractedAt());
        }
        if (updatedMemory.getIsDeleted() == null) {
            updatedMemory.setIsDeleted(existing.getIsDeleted());
        }
    }

    private void cleanupOldEmbeddingQuietly(String oldEmbeddingId, String newEmbeddingId, Long memoryId) {
        if (oldEmbeddingId == null || oldEmbeddingId.isBlank() || oldEmbeddingId.equals(newEmbeddingId)) {
            return;
        }

        try {
            vectorStoreService.deleteGroupMemory(oldEmbeddingId);
        } catch (Exception e) {
            log.error("Failed to delete old Milvus embedding after MySQL switch, compensation may be required - memoryId={}, oldEmbeddingId={}",
                    memoryId, oldEmbeddingId, e);
        }
    }

    private void cleanupNewEmbeddingOnFailure(String newEmbeddingId, Long memoryId, Exception cause) {
        if (newEmbeddingId == null || newEmbeddingId.isBlank()) {
            return;
        }

        try {
            vectorStoreService.deleteGroupMemory(newEmbeddingId);
        } catch (Exception cleanupEx) {
            log.error("Failed to rollback new Milvus embedding after refresh failure, compensation required - memoryId={}, newEmbeddingId={}",
                    memoryId, newEmbeddingId, cleanupEx);
        }

        log.error("Confirmed memory refresh failed - memoryId={}, newEmbeddingId={}",
                memoryId, newEmbeddingId, cause);
    }
}
