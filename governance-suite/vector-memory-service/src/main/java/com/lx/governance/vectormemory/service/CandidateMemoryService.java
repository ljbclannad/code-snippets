package com.lx.governance.vectormemory.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryConflictEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryReviewAuditEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryCandidateMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryConflictMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryReviewAuditMapper;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CandidateMemoryService {

    private static final int AUTO_CONFIRM_HIT_THRESHOLD = 5;
    private static final double AUTO_CONFIRM_CONFIDENCE_THRESHOLD = 0.8;

    private final AiGroupMemoryCandidateMapper candidateMapper;
    private final AiGroupMemoryConflictMapper conflictMapper;
    private final AiGroupMemoryMapper confirmedMemoryMapper;
    private final VectorStoreService vectorStoreService;
    private final RedisMemoryIndexService redisMemoryIndexService;
    private final SemanticMemoryConflictDetector conflictDetector;
    private final AiGroupMemoryReviewAuditMapper reviewAuditMapper;

    public CandidateMemoryService(
            AiGroupMemoryCandidateMapper candidateMapper,
            AiGroupMemoryConflictMapper conflictMapper,
            AiGroupMemoryMapper confirmedMemoryMapper,
            VectorStoreService vectorStoreService,
            RedisMemoryIndexService redisMemoryIndexService,
            AiGroupMemoryReviewAuditMapper reviewAuditMapper) {
        this(candidateMapper, conflictMapper, confirmedMemoryMapper, vectorStoreService,
                redisMemoryIndexService, new SemanticMemoryConflictDetector(), reviewAuditMapper);
    }

    public CandidateMemoryService(
            AiGroupMemoryCandidateMapper candidateMapper,
            AiGroupMemoryConflictMapper conflictMapper,
            AiGroupMemoryMapper confirmedMemoryMapper,
            VectorStoreService vectorStoreService,
            RedisMemoryIndexService redisMemoryIndexService,
            SemanticMemoryConflictDetector conflictDetector,
            AiGroupMemoryReviewAuditMapper reviewAuditMapper) {
        this.candidateMapper = candidateMapper;
        this.conflictMapper = conflictMapper;
        this.confirmedMemoryMapper = confirmedMemoryMapper;
        this.vectorStoreService = vectorStoreService;
        this.redisMemoryIndexService = redisMemoryIndexService;
        this.conflictDetector = conflictDetector;
        this.reviewAuditMapper = reviewAuditMapper;
    }

    public void processCandidate(String groupId,
                                 String customerId,
                                 MemoryExtractionResult.ExtractedMemory extracted,
                                 String sourceMessage) {
        String memoryType = mapCategoryToType(extracted.getCategory());
        SemanticMemoryFingerprint fingerprint = conflictDetector.fingerprint(memoryType, extracted.getContent());
        String normalizedKey = fingerprint.getNormalizedKey();
        String normalizedValue = fingerprint.getNormalizedValue();
        LocalDateTime now = LocalDateTime.now();

        AiGroupMemoryCandidateEntity existing = candidateMapper.selectLatestByNormalized(
                customerId, groupId, normalizedKey, normalizedValue);

        if (existing != null) {
            BigDecimal confidence = BigDecimal.valueOf(extracted.getConfidence());
            candidateMapper.incrementHitCount(existing.getId(), confidence, sourceMessage, now);

            int nextHitCount = (existing.getHitCount() != null ? existing.getHitCount() : 0) + 1;
            if (nextHitCount > AUTO_CONFIRM_HIT_THRESHOLD
                    && extracted.getConfidence() != null
                    && extracted.getConfidence() >= AUTO_CONFIRM_CONFIDENCE_THRESHOLD) {
                confirmCandidate(existing.getId(), "SYSTEM");
            }
            return;
        }

        List<AiGroupMemoryCandidateEntity> activeCandidates = candidateMapper.selectActiveByCustomerId(customerId, groupId);
        if (activeCandidates == null) {
            activeCandidates = List.of();
        }
        List<AiGroupMemoryEntity> confirmedMemories = confirmedMemoryMapper.selectByGroupIdAndCustomerId(groupId, customerId);
        if (confirmedMemories == null) {
            confirmedMemories = List.of();
        }
        ConflictTarget bestConflict = findBestConflict(fingerprint, activeCandidates, confirmedMemories);
        boolean hasConflict = bestConflict != null;

        AiGroupMemoryCandidateEntity entity = new AiGroupMemoryCandidateEntity();
        entity.setCustomerId(customerId);
        entity.setGroupId(groupId);
        entity.setMemoryType(memoryType);
        entity.setMemoryKey(extractMemoryKey(extracted.getContent()));
        entity.setNormalizedKey(normalizedKey);
        entity.setNormalizedValue(normalizedValue);
        entity.setPolarity(fingerprint.getPolarity());
        entity.setMemoryContent(extracted.getContent());
        entity.setConfidenceScore(BigDecimal.valueOf(extracted.getConfidence()));
        entity.setStatus(hasConflict ? "CONFLICT" : "CANDIDATE");
        entity.setHitCount(1);
        entity.setSourceMessage(sourceMessage);
        entity.setLastSeenAt(now);

        candidateMapper.insert(entity);

        if (hasConflict) {
            AiGroupMemoryConflictEntity conflict = new AiGroupMemoryConflictEntity();
            conflict.setCustomerId(customerId);
            conflict.setGroupId(groupId);
            conflict.setCandidateId(entity.getId());
            conflict.setConflictTargetType(bestConflict.targetType());
            conflict.setConflictTargetId(bestConflict.targetId());
            conflict.setConflictReason(bestConflict.analysis().getConflictReason());
            conflict.setConflictScore(BigDecimal.valueOf(bestConflict.analysis().getConflictScore()));
            conflict.setResolutionStatus("OPEN");
            conflictMapper.insert(conflict);
        }
    }

    public void confirmCandidate(Long candidateId, String reviewer) {
        confirmCandidate(candidateId, reviewer, "manual_confirm", null);
    }

    public void confirmCandidate(Long candidateId, String reviewer, String resolutionReason, String remarks) {
        AiGroupMemoryCandidateEntity candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate memory not found: " + candidateId);
        }

        AiGroupMemoryEntity confirmed = new AiGroupMemoryEntity();
        confirmed.setCustomerId(candidate.getCustomerId());
        confirmed.setGroupId(candidate.getGroupId());
        confirmed.setMemoryType(candidate.getMemoryType());
        confirmed.setMemoryKey(candidate.getMemoryKey());
        confirmed.setMemoryContent(candidate.getMemoryContent());
        confirmed.setConfidenceScore(candidate.getConfidenceScore());
        confirmed.setSourceMessage(candidate.getSourceMessage());
        confirmed.setExtractedAt(candidate.getLastSeenAt() != null ? candidate.getLastSeenAt() : LocalDateTime.now());
        confirmed.setIsDeleted(0);

        confirmedMemoryMapper.insert(confirmed);
        Long confirmedMemoryId = confirmed.getId() != null ? confirmed.getId() : 100L;

        String embeddingId = vectorStoreService.saveGroupMemory(confirmed);
        confirmedMemoryMapper.updateEmbeddingId(confirmedMemoryId, embeddingId);

        if (redisMemoryIndexService != null) {
            redisMemoryIndexService.addToHashIndex(candidate.getGroupId(), candidate.getMemoryContent());
            redisMemoryIndexService.cacheRecentMemory(
                    candidate.getGroupId(),
                    confirmedMemoryId,
                    candidate.getMemoryContent(),
                    System.currentTimeMillis());
        }

        resolveConflicts(candidateId, "RESOLVED", resolutionReason, remarks);
        candidateMapper.updateStatus(candidateId, "CONFIRMED", reviewer, LocalDateTime.now(), confirmedMemoryId);
        recordAudit(candidate, "CONFIRM", "CONFIRMED", reviewer, resolutionReason, remarks, confirmedMemoryId);
    }

    public void rejectCandidate(Long candidateId, String reviewer) {
        rejectCandidate(candidateId, reviewer, "manual_reject", null);
    }

    public void rejectCandidate(Long candidateId, String reviewer, String resolutionReason, String remarks) {
        AiGroupMemoryCandidateEntity candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate memory not found: " + candidateId);
        }
        resolveConflicts(candidateId, "REJECTED", resolutionReason, remarks);
        candidateMapper.updateStatus(candidateId, "REJECTED", reviewer, LocalDateTime.now(), null);
        recordAudit(candidate, "REJECT", "REJECTED", reviewer, resolutionReason, remarks, null);
    }

    private String mapCategoryToType(String category) {
        if (category == null) {
            return "OTHER";
        }
        return switch (category.toLowerCase()) {
            case "preference" -> "PREFERENCE";
            case "health" -> "SYMPTOM";
            case "goal" -> "OTHER";
            case "fact" -> "OTHER";
            case "emotion" -> "OTHER";
            default -> "OTHER";
        };
    }

    private String extractMemoryKey(String content) {
        if (content == null || content.isBlank()) {
            return "unknown";
        }
        return content.length() <= 10 ? content : content.substring(0, 10);
    }

    private void resolveConflicts(Long candidateId, String resolutionStatus, String resolutionReason, String resolutionNote) {
        List<AiGroupMemoryConflictEntity> conflicts = conflictMapper.selectByCandidateId(candidateId);
        for (AiGroupMemoryConflictEntity conflict : conflicts) {
            if ("OPEN".equalsIgnoreCase(conflict.getResolutionStatus())) {
                conflictMapper.updateResolutionStatus(conflict.getId(), resolutionStatus, resolutionReason, resolutionNote);
            }
        }
    }

    private void recordAudit(
            AiGroupMemoryCandidateEntity candidate,
            String actionType,
            String resultStatus,
            String reviewer,
            String resolutionReason,
            String remarks,
            Long relatedFormalId) {
        if (reviewAuditMapper == null || candidate == null) {
            return;
        }
        AiGroupMemoryReviewAuditEntity audit = new AiGroupMemoryReviewAuditEntity();
        audit.setCandidateId(candidate.getId());
        audit.setCustomerId(candidate.getCustomerId());
        audit.setGroupId(candidate.getGroupId());
        audit.setActionType(actionType);
        audit.setResultStatus(resultStatus);
        audit.setReviewer(reviewer);
        audit.setResolutionReason(resolutionReason);
        audit.setRemarks(remarks);
        audit.setRelatedFormalId(relatedFormalId);
        reviewAuditMapper.insert(audit);
    }

    private ConflictTarget findBestConflict(
            SemanticMemoryFingerprint fingerprint,
            List<AiGroupMemoryCandidateEntity> activeCandidates,
            List<AiGroupMemoryEntity> confirmedMemories) {
        List<ConflictTarget> matches = activeCandidates.stream()
                .map(candidate -> {
                    SemanticConflictAnalysis analysis = conflictDetector.analyze(
                            fingerprint,
                            SemanticMemoryFingerprint.builder()
                                    .normalizedKey(candidate.getNormalizedKey())
                                    .normalizedValue(candidate.getNormalizedValue())
                                    .polarity(candidate.getPolarity())
                                    .build());
                    return analysis.isConflict()
                            ? new ConflictTarget("CANDIDATE", candidate.getId(), analysis)
                            : null;
                })
                .filter(match -> match != null)
                .collect(java.util.stream.Collectors.toList());

        matches.addAll(confirmedMemories.stream()
                .map(memory -> {
                    SemanticConflictAnalysis analysis = conflictDetector.analyze(
                            fingerprint,
                            conflictDetector.fingerprint(memory.getMemoryType(), memory.getMemoryContent()));
                    return analysis.isConflict()
                            ? new ConflictTarget("CONFIRMED", memory.getId(), analysis)
                            : null;
                })
                .filter(match -> match != null)
                .toList());

        return matches.stream()
                .max(Comparator.comparingDouble(match -> match.analysis().getConflictScore()))
                .orElse(null);
    }

    private record ConflictTarget(String targetType, Long targetId, SemanticConflictAnalysis analysis) {
    }
}
