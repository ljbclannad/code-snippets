package com.lx.governance.vectormemory.domain.lifecycle;

import com.lx.governance.vectormemory.integration.storage.RedisMemoryIndexService;
import com.lx.governance.vectormemory.integration.storage.VectorStoreService;
import com.lx.governance.vectormemory.support.observability.MemoryLifecycleAuditService;
import com.lx.governance.vectormemory.support.observability.MemoryLifecycleMetricsService;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.config.MemoryLifecycleProperties;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryDecayService {

    private final AiGroupMemoryMapper memoryMapper;
    private final MemoryRetentionPolicy retentionPolicy;
    private final RedisMemoryIndexService redisMemoryIndexService;
    private final VectorStoreService vectorStoreService;
    private final MemoryLifecycleProperties lifecycleProperties;
    private final MemoryLifecycleMetricsService memoryLifecycleMetricsService;
    private final MemoryLifecycleAuditService memoryLifecycleAuditService;

    public DecayBatchResult processLifecycleMaintenance(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<AiGroupMemoryEntity> candidates = memoryMapper.selectLifecycleScanCandidates(limit);
        if (candidates == null || candidates.isEmpty()) {
            return new DecayBatchResult(0, 0, 0, 0);
        }

        int decayed = 0;
        int archived = 0;
        int deleted = 0;
        int unchanged = 0;

        for (AiGroupMemoryEntity memory : candidates) {
            retentionPolicy.applyDefaults(memory);
            MemoryRetentionPolicy.RetentionDecision decision = retentionPolicy.evaluate(memory, now);
            switch (decision.getAction()) {
                case "DECAY" -> {
                    decayMemory(memory, decision, now);
                    decayed++;
                }
                case "ARCHIVE" -> {
                    archiveMemory(memory, decision, now);
                    archived++;
                }
                case "DELETE" -> {
                    deleteMemory(memory, decision);
                    deleted++;
                }
                default -> unchanged++;
            }
        }

        return new DecayBatchResult(decayed, archived, deleted, unchanged);
    }

    public void applyRetentionDefaults(AiGroupMemoryEntity memory) {
        retentionPolicy.applyDefaults(memory);
    }

    private void decayMemory(AiGroupMemoryEntity memory,
                             MemoryRetentionPolicy.RetentionDecision decision,
                             LocalDateTime now) {
        double baseDecay = memory.getDecayScore() != null ? memory.getDecayScore().doubleValue() : 0.0d;
        double nextDecay = Math.max(baseDecay, lifecycleProperties.getDecay().getDefaultDecayScore());
        memoryMapper.updateLifecycleState(
                memory.getId(),
                "DECAYING",
                nextDecay,
                memory.getRetentionPolicy(),
                memory.getExpiresAt(),
                decision.getReasonCode(),
                null,
                null,
                now);
        memoryLifecycleAuditService.recordStateEvent("CONFIRMED", memory.getId(), memory.getCustomerId(), memory.getGroupId(),
                memory.getMemoryType(), "DECAYED", "DECAYING", decision.getReasonCode(), decision.getSummary());
        log.info("memory decayed - memoryId: {}, reason: {}, decayScore: {}", memory.getId(), decision.getReasonCode(), nextDecay);
    }

    private void archiveMemory(AiGroupMemoryEntity memory,
                               MemoryRetentionPolicy.RetentionDecision decision,
                               LocalDateTime now) {
        memoryMapper.updateLifecycleState(
                memory.getId(),
                "ARCHIVED",
                memory.getDecayScore() != null ? memory.getDecayScore().doubleValue() : lifecycleProperties.getDecay().getDefaultDecayScore(),
                memory.getRetentionPolicy(),
                memory.getExpiresAt(),
                decision.getReasonCode(),
                now,
                decision.getSummary(),
                now);
        evictRedisIndexes(memory);
        memoryLifecycleMetricsService.recordArchive();
        memoryLifecycleAuditService.recordStateEvent("CONFIRMED", memory.getId(), memory.getCustomerId(), memory.getGroupId(),
                memory.getMemoryType(), "ARCHIVED", "ARCHIVED", decision.getReasonCode(), decision.getSummary());
        log.info("memory archived - memoryId: {}, reason: {}", memory.getId(), decision.getReasonCode());
    }

    private void deleteMemory(AiGroupMemoryEntity memory,
                              MemoryRetentionPolicy.RetentionDecision decision) {
        memoryMapper.deleteById(memory.getId());
        if (memory.getEmbeddingId() != null && !memory.getEmbeddingId().isBlank()) {
            vectorStoreService.deleteGroupMemory(memory.getEmbeddingId());
        }
        evictRedisIndexes(memory);
        memoryLifecycleMetricsService.recordDelete();
        memoryLifecycleAuditService.recordStateEvent("CONFIRMED", memory.getId(), memory.getCustomerId(), memory.getGroupId(),
                memory.getMemoryType(), "DELETED", "DELETED", decision.getReasonCode(), decision.getSummary());
        log.info("memory deleted - memoryId: {}, reason: {}", memory.getId(), decision.getReasonCode());
    }

    private void evictRedisIndexes(AiGroupMemoryEntity memory) {
        if (redisMemoryIndexService == null) {
            return;
        }
        redisMemoryIndexService.removeFromHashIndex(memory.getGroupId(), memory.getMemoryContent());
        redisMemoryIndexService.removeRecentMemory(memory.getGroupId(), memory.getId(), memory.getMemoryContent());
    }

    public record DecayBatchResult(int decayed, int archived, int deleted, int unchanged) {
    }
}
