package com.lx.governance.vectormemory.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.enums.CandidateStatus;
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
    private final MemoryTypeNormalizer memoryTypeNormalizer;

    @Autowired
    public CandidateMemoryService(
            AiGroupMemoryCandidateMapper candidateMapper,
            AiGroupMemoryConflictMapper conflictMapper,
            AiGroupMemoryMapper confirmedMemoryMapper,
            VectorStoreService vectorStoreService,
            RedisMemoryIndexService redisMemoryIndexService,
            MemoryTypeNormalizer memoryTypeNormalizer,
            AiGroupMemoryReviewAuditMapper reviewAuditMapper) {
        this(candidateMapper, conflictMapper, confirmedMemoryMapper, vectorStoreService,
                redisMemoryIndexService, new SemanticMemoryConflictDetector(), reviewAuditMapper, memoryTypeNormalizer);
    }

    public CandidateMemoryService(
            AiGroupMemoryCandidateMapper candidateMapper,
            AiGroupMemoryConflictMapper conflictMapper,
            AiGroupMemoryMapper confirmedMemoryMapper,
            VectorStoreService vectorStoreService,
            RedisMemoryIndexService redisMemoryIndexService,
            SemanticMemoryConflictDetector conflictDetector,
            AiGroupMemoryReviewAuditMapper reviewAuditMapper,
            MemoryTypeNormalizer memoryTypeNormalizer) {
        this.candidateMapper = candidateMapper;
        this.conflictMapper = conflictMapper;
        this.confirmedMemoryMapper = confirmedMemoryMapper;
        this.vectorStoreService = vectorStoreService;
        this.redisMemoryIndexService = redisMemoryIndexService;
        this.conflictDetector = conflictDetector;
        this.reviewAuditMapper = reviewAuditMapper;
        this.memoryTypeNormalizer = memoryTypeNormalizer;
    }

    public void processCandidate(String groupId,
                                 String customerId,
                                 MemoryExtractionResult.ExtractedMemory extracted,
                                 String sourceMessage) {
        String memoryType = normalizeMemoryType(extracted.getCategory());
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
                handleAutoConfirmationDecision(existing.getId(), memoryType, fingerprint, now);
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
        entity.setStatus(hasConflict ? CandidateStatus.CONFLICT.name() : CandidateStatus.CANDIDATE.name());
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
        candidateMapper.updateStatus(candidateId, CandidateStatus.CONFIRMED.name(), reviewer, LocalDateTime.now(), confirmedMemoryId);
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
        resolveConflicts(candidateId, CandidateStatus.REJECTED.name(), resolutionReason, remarks);
        candidateMapper.updateStatus(candidateId, CandidateStatus.REJECTED.name(), reviewer, LocalDateTime.now(), null);
        recordAudit(candidate, "REJECT", CandidateStatus.REJECTED.name(), reviewer, resolutionReason, remarks, null);
    }

    private void handleAutoConfirmationDecision(Long candidateId,
                                                String memoryType,
                                                SemanticMemoryFingerprint fingerprint,
                                                LocalDateTime reviewedAt) {
        AiGroupMemoryCandidateEntity candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate memory not found: " + candidateId);
        }

        List<AiGroupMemoryEntity> confirmedMemories = confirmedMemoryMapper.selectByGroupIdAndCustomerId(
                candidate.getGroupId(), candidate.getCustomerId());
        if (confirmedMemories == null || confirmedMemories.isEmpty()) {
            confirmCandidate(candidateId, "SYSTEM", "auto_confirm", null);
            return;
        }

        ConfirmedMemoryMatch match = findConfirmedMemoryMatch(memoryType, fingerprint, confirmedMemories);
        if (match == null) {
            confirmCandidate(candidateId, "SYSTEM", "auto_confirm", null);
            return;
        }

        if (match.equivalent()) {
            absorbIntoConfirmed(candidate, match.memory(), reviewedAt);
            return;
        }

        moveToConflict(candidate, match.memory(), match.analysis(), reviewedAt);
    }

    private void absorbIntoConfirmed(AiGroupMemoryCandidateEntity candidate,
                                     AiGroupMemoryEntity confirmedMemory,
                                     LocalDateTime reviewedAt) {
        resolveConflicts(candidate.getId(), "RESOLVED", "duplicate_confirmed_memory", null);
        candidateMapper.updateStatus(candidate.getId(), CandidateStatus.CONFIRMED.name(), "SYSTEM", reviewedAt, confirmedMemory.getId());
        recordAudit(candidate, "AUTO_MERGE", CandidateStatus.CONFIRMED.name(), "SYSTEM",
                "duplicate_confirmed_memory", null, confirmedMemory.getId());
    }

    private void moveToConflict(AiGroupMemoryCandidateEntity candidate,
                                AiGroupMemoryEntity confirmedMemory,
                                SemanticConflictAnalysis analysis,
                                LocalDateTime reviewedAt) {
        candidateMapper.updateStatus(candidate.getId(), CandidateStatus.CONFLICT.name(), "SYSTEM", reviewedAt, null);

        boolean hasOpenConflict = conflictMapper.selectByCandidateId(candidate.getId()).stream()
                .anyMatch(conflict -> "OPEN".equalsIgnoreCase(conflict.getResolutionStatus())
                        && "CONFIRMED".equalsIgnoreCase(conflict.getConflictTargetType())
                        && confirmedMemory.getId().equals(conflict.getConflictTargetId()));

        if (!hasOpenConflict) {
            AiGroupMemoryConflictEntity conflict = new AiGroupMemoryConflictEntity();
            conflict.setCustomerId(candidate.getCustomerId());
            conflict.setGroupId(candidate.getGroupId());
            conflict.setCandidateId(candidate.getId());
            conflict.setConflictTargetType("CONFIRMED");
            conflict.setConflictTargetId(confirmedMemory.getId());
            conflict.setConflictReason(analysis != null ? analysis.getConflictReason() : "confirmed_memory_exists");
            conflict.setConflictScore(BigDecimal.valueOf(analysis != null ? analysis.getConflictScore() : 1.0d));
            conflict.setResolutionStatus("OPEN");
            conflictMapper.insert(conflict);
        }

        recordAudit(candidate, "REVIEW_REQUIRED", CandidateStatus.CONFLICT.name(), "SYSTEM",
                "confirmed_memory_exists", null, confirmedMemory.getId());
    }

    private String normalizeMemoryType(String rawType) {
        return memoryTypeNormalizer.normalize(rawType);
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

    private ConfirmedMemoryMatch findConfirmedMemoryMatch(String memoryType,
                                                          SemanticMemoryFingerprint fingerprint,
                                                          List<AiGroupMemoryEntity> confirmedMemories) {
        return confirmedMemories.stream()
                .map(memory -> {
                    SemanticMemoryFingerprint confirmedFingerprint = conflictDetector.fingerprint(
                            memory.getMemoryType(), memory.getMemoryContent());

                    boolean equivalent = equalsIgnoreCase(fingerprint.getNormalizedKey(), confirmedFingerprint.getNormalizedKey())
                            && equalsIgnoreCase(fingerprint.getNormalizedValue(), confirmedFingerprint.getNormalizedValue())
                            && equalsIgnoreCase(fingerprint.getPolarity(), confirmedFingerprint.getPolarity());
                    if (equivalent) {
                        return new ConfirmedMemoryMatch(memory, null, true);
                    }

                    boolean sameSlot = equalsIgnoreCase(fingerprint.getNormalizedKey(), confirmedFingerprint.getNormalizedKey())
                            && equalsIgnoreCase(memoryType, memory.getMemoryType());
                    if (!sameSlot) {
                        return null;
                    }

                    SemanticConflictAnalysis analysis = conflictDetector.analyze(fingerprint, confirmedFingerprint);
                    return new ConfirmedMemoryMatch(memory, analysis, false);
                })
                .filter(match -> match != null)
                .findFirst()
                .orElse(null);
    }

    private boolean equalsIgnoreCase(String left, String right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private record ConflictTarget(String targetType, Long targetId, SemanticConflictAnalysis analysis) {
    }

    private record ConfirmedMemoryMatch(
            AiGroupMemoryEntity memory,
            SemanticConflictAnalysis analysis,
            boolean equivalent) {
    }
}
