package com.lx.governance.vectormemory.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lx.governance.vectormemory.dto.CandidateMemoryLifecycleView;
import com.lx.governance.vectormemory.dto.MemoryLifecycleHistoryView;
import com.lx.governance.vectormemory.dto.MemoryLifecycleReplayView;
import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryCandidateMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryConflictMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryLifecycleEventMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryReviewAuditMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemoryLifecycleGovernanceService {

    private final AiGroupMemoryCandidateMapper candidateMapper;
    private final AiGroupMemoryConflictMapper conflictMapper;
    private final AiGroupMemoryReviewAuditMapper reviewAuditMapper;
    private final AiGroupMemoryMapper memoryMapper;
    private final AiGroupMemoryLifecycleEventMapper lifecycleEventMapper;
    private final ConfirmedMemoryMaintenanceService confirmedMemoryMaintenanceService;
    private final RedisMemoryIndexService redisMemoryIndexService;
    private final VectorStoreService vectorStoreService;
    private final MemoryLifecycleAuditService memoryLifecycleAuditService;
    private final MemoryLifecycleMetricsService memoryLifecycleMetricsService;

    public MemoryLifecycleReplayView buildReplayView(String groupId, String customerId, String memoryType, int limit) {
        return MemoryLifecycleReplayView.builder()
                .groupId(groupId)
                .customerId(customerId)
                .memoryType(memoryType)
                .events(lifecycleEventMapper.selectRecentByScope(customerId, groupId, memoryType, limit))
                .build();
    }

    public CandidateMemoryLifecycleView getCandidateLifecycleView(Long candidateId) {
        AiGroupMemoryCandidateEntity candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate memory not found: " + candidateId);
        }
        return CandidateMemoryLifecycleView.builder()
                .candidate(candidate)
                .conflicts(conflictMapper.selectByCandidateId(candidateId))
                .audits(reviewAuditMapper.selectByCandidateId(candidateId))
                .events(lifecycleEventMapper.selectByEntity("CANDIDATE", candidateId))
                .relatedFormalMemory(candidate.getConfirmedMemoryId() == null
                        ? null
                        : memoryMapper.selectById(candidate.getConfirmedMemoryId()))
                .build();
    }

    public MemoryLifecycleHistoryView getMemoryHistory(Long memoryId) {
        AiGroupMemoryEntity memory = requireMemory(memoryId);
        return MemoryLifecycleHistoryView.builder()
                .memory(memory)
                .events(lifecycleEventMapper.selectByEntity("CONFIRMED", memoryId))
                .build();
    }

    public List<AiGroupMemoryCandidateEntity> listCandidates(String groupId, String customerId, List<String> statuses) {
        return candidateMapper.selectByCustomerIdAndGroupId(customerId, groupId, statuses);
    }

    public List<AiGroupMemoryEntity> listMemories(String groupId, String customerId, String lifecycleStatus) {
        return memoryMapper.selectGovernanceList(groupId, customerId, lifecycleStatus);
    }

    @Transactional
    public void archiveMemory(Long memoryId, String reviewer, String reasonCode, String remarks) {
        AiGroupMemoryEntity memory = requireMemory(memoryId);
        String appliedReason = reasonCodeOrDefault(reasonCode, "manual_archive");
        LocalDateTime now = LocalDateTime.now();
        memoryMapper.updateLifecycleState(
                memoryId,
                "ARCHIVED",
                currentDecay(memory),
                memory.getRetentionPolicy(),
                memory.getExpiresAt(),
                appliedReason,
                now,
                remarks,
                now);
        evictRedis(memory);
        memoryLifecycleMetricsService.recordArchive();
        memoryLifecycleAuditService.recordStateEvent("CONFIRMED", memoryId, memory.getCustomerId(), memory.getGroupId(),
                memory.getMemoryType(), "MANUAL_ARCHIVED", "ARCHIVED", appliedReason, actorPayload(reviewer, remarks));
    }

    @Transactional
    public void restoreMemory(Long memoryId, String reviewer, String reasonCode, String remarks) {
        AiGroupMemoryEntity memory = requireMemory(memoryId);
        String appliedReason = reasonCodeOrDefault(reasonCode, "manual_restore");
        memoryMapper.updateLifecycleState(
                memoryId,
                "CONFIRMED",
                currentDecay(memory),
                memory.getRetentionPolicy(),
                memory.getExpiresAt(),
                appliedReason,
                null,
                null,
                LocalDateTime.now());
        if (redisMemoryIndexService != null) {
            redisMemoryIndexService.addToHashIndex(memory.getGroupId(), memory.getMemoryContent());
            redisMemoryIndexService.cacheRecentMemory(memory.getGroupId(), memory.getId(), memory.getMemoryContent(),
                    System.currentTimeMillis());
        }
        memoryLifecycleAuditService.recordStateEvent("CONFIRMED", memoryId, memory.getCustomerId(), memory.getGroupId(),
                memory.getMemoryType(), "RESTORED", "CONFIRMED", appliedReason, actorPayload(reviewer, remarks));
    }

    @Transactional
    public void deleteMemory(Long memoryId, String reviewer, String reasonCode, String remarks) {
        AiGroupMemoryEntity memory = requireMemory(memoryId);
        memoryMapper.deleteById(memoryId);
        if (memory.getEmbeddingId() != null && !memory.getEmbeddingId().isBlank()) {
            vectorStoreService.deleteGroupMemory(memory.getEmbeddingId());
        }
        evictRedis(memory);
        memoryLifecycleMetricsService.recordDelete();
        memoryLifecycleAuditService.recordStateEvent("CONFIRMED", memoryId, memory.getCustomerId(), memory.getGroupId(),
                memory.getMemoryType(), "MANUAL_DELETED", "DELETED",
                reasonCodeOrDefault(reasonCode, "manual_delete"), actorPayload(reviewer, remarks));
    }

    @Transactional
    public void mergeMemories(Long sourceMemoryId, Long targetMemoryId, String reviewer, String reasonCode, String remarks) {
        if (sourceMemoryId == null || targetMemoryId == null || sourceMemoryId.equals(targetMemoryId)) {
            throw new IllegalArgumentException("Source and target memory ids must be different");
        }

        AiGroupMemoryEntity source = requireMemory(sourceMemoryId);
        AiGroupMemoryEntity target = requireMemory(targetMemoryId);
        validateMergeScope(source, target);

        AiGroupMemoryEntity merged = new AiGroupMemoryEntity();
        merged.setId(target.getId());
        merged.setCustomerId(target.getCustomerId());
        merged.setGroupId(target.getGroupId());
        merged.setMemoryType(target.getMemoryType());
        merged.setMemoryKey(target.getMemoryKey());
        merged.setMemoryContent(target.getMemoryContent());
        merged.setMemoryRepresentation(target.getMemoryRepresentation());
        merged.setSummaryGroupKey(target.getSummaryGroupKey());
        merged.setSummaryOfMemoryId(target.getSummaryOfMemoryId());
        merged.setLifecycleStatus("CONFIRMED");
        merged.setRetentionPolicy(target.getRetentionPolicy());
        merged.setExpiresAt(target.getExpiresAt());
        merged.setDecayScore(target.getDecayScore());
        merged.setLastAccessedAt(LocalDateTime.now());
        merged.setLastReinforcedAt(LocalDateTime.now());
        merged.setSourceCount((target.getSourceCount() == null ? 1 : target.getSourceCount())
                + (source.getSourceCount() == null ? 1 : source.getSourceCount()));
        merged.setEvidenceCount((target.getEvidenceCount() == null ? 1 : target.getEvidenceCount())
                + (source.getEvidenceCount() == null ? 1 : source.getEvidenceCount()));
        merged.setConflictStatus(target.getConflictStatus());
        merged.setSupersededBy(target.getSupersededBy());
        merged.setArchivedAt(target.getArchivedAt());
        merged.setArchiveReason(target.getArchiveReason());
        merged.setDecisionReasonCode(reasonCodeOrDefault(reasonCode, "manual_merge"));
        merged.setEmbeddingId(target.getEmbeddingId());
        merged.setConfidenceScore(max(target.getConfidenceScore(), source.getConfidenceScore()));
        merged.setSourceMessage(source.getSourceMessage() != null ? source.getSourceMessage() : target.getSourceMessage());
        merged.setExtractedAt(latestTime(target.getExtractedAt(), source.getExtractedAt()));
        merged.setIsDeleted(target.getIsDeleted());
        confirmedMemoryMaintenanceService.refreshConfirmedMemory(merged);

        source.setLifecycleStatus("ARCHIVED");
        source.setSupersededBy(targetMemoryId);
        source.setArchivedAt(LocalDateTime.now());
        source.setArchiveReason(reasonCodeOrDefault(reasonCode, "manual_merge"));
        source.setDecisionReasonCode(reasonCodeOrDefault(reasonCode, "manual_merge"));
        memoryMapper.updateConfirmedMemory(source);
        evictRedis(source);
        memoryLifecycleAuditService.recordStateEvent("CONFIRMED", targetMemoryId, target.getCustomerId(), target.getGroupId(),
                target.getMemoryType(), "MERGED_IN", "CONFIRMED",
                reasonCodeOrDefault(reasonCode, "manual_merge"), actorPayload(reviewer, remarks));
        memoryLifecycleAuditService.recordStateEvent("CONFIRMED", sourceMemoryId, source.getCustomerId(), source.getGroupId(),
                source.getMemoryType(), "MERGED_OUT", "ARCHIVED",
                reasonCodeOrDefault(reasonCode, "manual_merge"),
                "merged_into=" + targetMemoryId + ", " + actorPayload(reviewer, remarks));
    }

    private AiGroupMemoryEntity requireMemory(Long memoryId) {
        AiGroupMemoryEntity memory = memoryMapper.selectById(memoryId);
        if (memory == null) {
            throw new IllegalArgumentException("Confirmed memory not found: " + memoryId);
        }
        return memory;
    }

    private void validateMergeScope(AiGroupMemoryEntity source, AiGroupMemoryEntity target) {
        if (!safeEquals(source.getCustomerId(), target.getCustomerId())
                || !safeEquals(source.getGroupId(), target.getGroupId())
                || !safeEquals(source.getMemoryType(), target.getMemoryType())) {
            throw new IllegalArgumentException("Only memories in the same customer/group/type scope can be merged");
        }
    }

    private void evictRedis(AiGroupMemoryEntity memory) {
        if (redisMemoryIndexService == null) {
            return;
        }
        redisMemoryIndexService.removeFromHashIndex(memory.getGroupId(), memory.getMemoryContent());
        redisMemoryIndexService.removeRecentMemory(memory.getGroupId(), memory.getId(), memory.getMemoryContent());
    }

    private double currentDecay(AiGroupMemoryEntity memory) {
        return memory.getDecayScore() == null ? 0.0d : memory.getDecayScore().doubleValue();
    }

    private String reasonCodeOrDefault(String reasonCode, String fallback) {
        return reasonCode == null || reasonCode.isBlank() ? fallback : reasonCode;
    }

    private String actorPayload(String reviewer, String remarks) {
        return "reviewer=" + (reviewer == null || reviewer.isBlank() ? "SYSTEM" : reviewer)
                + (remarks == null || remarks.isBlank() ? "" : ", remarks=" + remarks);
    }

    private LocalDateTime latestTime(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.max(right);
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
