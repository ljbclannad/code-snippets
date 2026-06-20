package com.lx.governance.vectormemory.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.dto.MemoryLifecycleDecision;
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
    private final MemoryConflictResolver memoryConflictResolver;
    private final MemoryMergeService memoryMergeService;
    private final ConfirmedMemoryMaintenanceService confirmedMemoryMaintenanceService;
    private final MemoryRetentionPolicy memoryRetentionPolicy;
    private final MemoryLifecycleAuditService memoryLifecycleAuditService;
    private final MemoryLifecycleMetricsService memoryLifecycleMetricsService;

    @Autowired
    public CandidateMemoryService(
            AiGroupMemoryCandidateMapper candidateMapper,
            AiGroupMemoryConflictMapper conflictMapper,
            AiGroupMemoryMapper confirmedMemoryMapper,
            VectorStoreService vectorStoreService,
            RedisMemoryIndexService redisMemoryIndexService,
            MemoryTypeNormalizer memoryTypeNormalizer,
            AiGroupMemoryReviewAuditMapper reviewAuditMapper,
            MemoryConflictResolver memoryConflictResolver,
            MemoryMergeService memoryMergeService,
            ConfirmedMemoryMaintenanceService confirmedMemoryMaintenanceService,
            MemoryRetentionPolicy memoryRetentionPolicy,
            MemoryLifecycleAuditService memoryLifecycleAuditService,
            MemoryLifecycleMetricsService memoryLifecycleMetricsService) {
        this(candidateMapper, conflictMapper, confirmedMemoryMapper, vectorStoreService,
                redisMemoryIndexService, new SemanticMemoryConflictDetector(), reviewAuditMapper, memoryTypeNormalizer,
                memoryConflictResolver, memoryMergeService, confirmedMemoryMaintenanceService, memoryRetentionPolicy,
                memoryLifecycleAuditService, memoryLifecycleMetricsService);
    }

    public CandidateMemoryService(
            AiGroupMemoryCandidateMapper candidateMapper,
            AiGroupMemoryConflictMapper conflictMapper,
            AiGroupMemoryMapper confirmedMemoryMapper,
            VectorStoreService vectorStoreService,
            RedisMemoryIndexService redisMemoryIndexService,
            SemanticMemoryConflictDetector conflictDetector,
            AiGroupMemoryReviewAuditMapper reviewAuditMapper,
            MemoryTypeNormalizer memoryTypeNormalizer,
            MemoryConflictResolver memoryConflictResolver,
            MemoryMergeService memoryMergeService,
            ConfirmedMemoryMaintenanceService confirmedMemoryMaintenanceService,
            MemoryRetentionPolicy memoryRetentionPolicy,
            MemoryLifecycleAuditService memoryLifecycleAuditService,
            MemoryLifecycleMetricsService memoryLifecycleMetricsService) {
        this.candidateMapper = candidateMapper;
        this.conflictMapper = conflictMapper;
        this.confirmedMemoryMapper = confirmedMemoryMapper;
        this.vectorStoreService = vectorStoreService;
        this.redisMemoryIndexService = redisMemoryIndexService;
        this.conflictDetector = conflictDetector;
        this.reviewAuditMapper = reviewAuditMapper;
        this.memoryTypeNormalizer = memoryTypeNormalizer;
        this.memoryConflictResolver = memoryConflictResolver;
        this.memoryMergeService = memoryMergeService;
        this.confirmedMemoryMaintenanceService = confirmedMemoryMaintenanceService;
        this.memoryRetentionPolicy = memoryRetentionPolicy;
        this.memoryLifecycleAuditService = memoryLifecycleAuditService;
        this.memoryLifecycleMetricsService = memoryLifecycleMetricsService;
    }

    public void processCandidate(String groupId,
                                 String customerId,
                                 MemoryExtractionResult.ExtractedMemory extracted,
                                 String sourceMessage) {
        processLifecycleDecision(MemoryLifecycleDecision.builder()
                .groupId(groupId)
                .customerId(customerId)
                .extractedMemory(extracted)
                .normalizedMemoryType(normalizeMemoryType(extracted.getCategory()))
                .sourceMessage(sourceMessage)
                .action(MemoryLifecycleDecision.LifecycleAction.INGEST_CANDIDATE)
                .lifecycleStatus(CandidateStatus.CANDIDATE.name())
                .decisionReasonCode("legacy_candidate_ingestion")
                .worthinessScore(BigDecimal.valueOf(extracted.getConfidence() == null ? 0.0d : extracted.getConfidence()))
                .decisionSummary("legacy processCandidate entry")
                .build());
    }

    public void processLifecycleDecision(MemoryLifecycleDecision decision) {
        if (decision == null || decision.getExtractedMemory() == null) {
            throw new IllegalArgumentException("Memory lifecycle decision is required");
        }

        String groupId = decision.getGroupId();
        String customerId = decision.getCustomerId();
        MemoryExtractionResult.ExtractedMemory extracted = decision.getExtractedMemory();
        String sourceMessage = decision.getSourceMessage();
        String memoryType = decision.getNormalizedMemoryType() != null
                ? decision.getNormalizedMemoryType()
                : normalizeMemoryType(extracted.getCategory());
        SemanticMemoryFingerprint fingerprint = conflictDetector.fingerprint(memoryType, extracted.getContent());
        String normalizedKey = fingerprint.getNormalizedKey();
        String normalizedValue = fingerprint.getNormalizedValue();
        LocalDateTime now = LocalDateTime.now();

        AiGroupMemoryCandidateEntity existing = candidateMapper.selectLatestByNormalized(
                customerId, groupId, normalizedKey, normalizedValue);

        if (existing != null) {
            BigDecimal confidence = BigDecimal.valueOf(extracted.getConfidence() == null ? 0.0d : extracted.getConfidence());
            candidateMapper.incrementHitCount(existing.getId(),
                    confidence,
                    decision.getWorthinessScore(),
                    decision.getDecisionReasonCode(),
                    sourceMessage,
                    now,
                    now);

            int nextHitCount = (existing.getHitCount() != null ? existing.getHitCount() : 0) + 1;
            if (nextHitCount > AUTO_CONFIRM_HIT_THRESHOLD
                    && extracted.getConfidence() != null
                    && extracted.getConfidence() >= AUTO_CONFIRM_CONFIDENCE_THRESHOLD) {
                handleAutoConfirmationDecision(existing.getId(), memoryType, fingerprint, now, decision.getDecisionReasonCode());
            }
            memoryLifecycleAuditService.recordStateEvent("CANDIDATE", existing.getId(), existing.getCustomerId(),
                    existing.getGroupId(), existing.getMemoryType(), "CANDIDATE_REINFORCED",
                    existing.getLifecycleStatus(), decision.getDecisionReasonCode(), extracted.getContent());
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
        entity.setConfidenceScore(BigDecimal.valueOf(extracted.getConfidence() == null ? 0.0d : extracted.getConfidence()));
        entity.setWorthinessScore(decision.getWorthinessScore());
        entity.setStatus(hasConflict ? CandidateStatus.CONFLICT.name() : CandidateStatus.CANDIDATE.name());
        entity.setLifecycleStatus(hasConflict ? CandidateStatus.CONFLICT.name() : decision.getLifecycleStatus());
        entity.setDecisionReasonCode(hasConflict ? "semantic_conflict_detected" : decision.getDecisionReasonCode());
        entity.setHitCount(1);
        entity.setSourceCount(1);
        entity.setEvidenceCount(1);
        entity.setSourceMessage(sourceMessage);
        entity.setLastSeenAt(now);
        entity.setLastReinforcedAt(now);

        candidateMapper.insert(entity);
        memoryLifecycleAuditService.recordStateEvent("CANDIDATE", entity.getId(), entity.getCustomerId(),
                entity.getGroupId(), entity.getMemoryType(), "CANDIDATE_CREATED",
                entity.getLifecycleStatus(), entity.getDecisionReasonCode(), entity.getMemoryContent());

        if (hasConflict) {
            memoryLifecycleMetricsService.recordConflict();
            memoryLifecycleAuditService.recordStateEvent("CANDIDATE", entity.getId(), entity.getCustomerId(),
                    entity.getGroupId(), entity.getMemoryType(), "CONFLICT_DETECTED",
                    CandidateStatus.CONFLICT.name(), "semantic_conflict_detected",
                    bestConflict.analysis().getConflictReason());
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

        ResolutionExecution resolutionExecution = resolveConfirmedMemoryExecution(candidate, resolutionReason);
        if (resolutionExecution != null) {
            applyResolutionExecution(candidate, resolutionExecution, reviewer, remarks);
            return;
        }

        Long confirmedMemoryId = insertConfirmedMemory(candidate, resolutionReason, false);

        resolveConflicts(candidateId, "RESOLVED", resolutionReason, remarks);
        candidateMapper.updateStatus(candidateId, CandidateStatus.CONFIRMED.name(), reviewer, LocalDateTime.now(), confirmedMemoryId);
        recordAudit(candidate, "CONFIRM", "CONFIRMED", reviewer, resolutionReason, resolutionReason, remarks, confirmedMemoryId);
        memoryLifecycleMetricsService.recordPromotion();
        memoryLifecycleAuditService.recordStateEvent("CANDIDATE", candidateId, candidate.getCustomerId(),
                candidate.getGroupId(), candidate.getMemoryType(), "PROMOTED",
                CandidateStatus.CONFIRMED.name(), resolutionReason, remarks);
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
        recordAudit(candidate, "REJECT", CandidateStatus.REJECTED.name(), reviewer, resolutionReason, resolutionReason, remarks, null);
        memoryLifecycleAuditService.recordStateEvent("CANDIDATE", candidateId, candidate.getCustomerId(),
                candidate.getGroupId(), candidate.getMemoryType(), "REJECTED",
                CandidateStatus.REJECTED.name(), resolutionReason, remarks);
    }

    private void handleAutoConfirmationDecision(Long candidateId,
                                                String memoryType,
                                                SemanticMemoryFingerprint fingerprint,
                                                LocalDateTime reviewedAt,
                                                String decisionReasonCode) {
        AiGroupMemoryCandidateEntity candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate memory not found: " + candidateId);
        }

        List<AiGroupMemoryEntity> confirmedMemories = confirmedMemoryMapper.selectByGroupIdAndCustomerId(
                candidate.getGroupId(), candidate.getCustomerId());
        if (confirmedMemories == null || confirmedMemories.isEmpty()) {
            confirmCandidate(candidateId, "SYSTEM", decisionReasonCode != null ? decisionReasonCode : "auto_confirm", null);
            return;
        }

        ConfirmedMemoryMatch match = findConfirmedMemoryMatch(memoryType, fingerprint, confirmedMemories);
        if (match == null) {
            confirmCandidate(candidateId, "SYSTEM", decisionReasonCode != null ? decisionReasonCode : "auto_confirm", null);
            return;
        }

        MemoryConflictResolution resolution = memoryConflictResolver.resolve(
                memoryType, fingerprint, candidate, match.memory());

        switch (resolution.getStrategy()) {
            case ABSORB_EXISTING -> absorbIntoConfirmed(candidate, match.memory(), reviewedAt, resolution.getReasonCode());
            case UPDATE_EXISTING_PREFERENCE -> updateExistingConfirmedMemory(candidate, match.memory(), reviewedAt, resolution);
            case APPEND_AS_VERSION -> confirmCandidate(candidateId, "SYSTEM", resolution.getReasonCode(), null);
            case APPEND_WITH_TIME_WINDOW -> appendSymptomWithTimeWindow(candidate, reviewedAt, resolution);
            case REQUIRE_REVIEW -> moveToConflict(candidate, match.memory(), match.analysis(), reviewedAt, resolution.getReasonCode());
            default -> moveToConflict(candidate, match.memory(), match.analysis(), reviewedAt, resolution.getReasonCode());
        }
    }

    private void absorbIntoConfirmed(AiGroupMemoryCandidateEntity candidate,
                                     AiGroupMemoryEntity confirmedMemory,
                                     LocalDateTime reviewedAt,
                                     String decisionReasonCode) {
        resolveConflicts(candidate.getId(), "RESOLVED", "duplicate_confirmed_memory", null);
        candidateMapper.updateStatus(candidate.getId(), CandidateStatus.CONFIRMED.name(), "SYSTEM", reviewedAt, confirmedMemory.getId());
        recordAudit(candidate, "AUTO_MERGE", CandidateStatus.CONFIRMED.name(), "SYSTEM",
                "duplicate_confirmed_memory",
                decisionReasonCode != null ? decisionReasonCode : "duplicate_confirmed_memory",
                null,
                confirmedMemory.getId());
        memoryLifecycleMetricsService.recordPromotion();
        memoryLifecycleAuditService.recordStateEvent("CANDIDATE", candidate.getId(), candidate.getCustomerId(),
                candidate.getGroupId(), candidate.getMemoryType(), "AUTO_MERGED",
                CandidateStatus.CONFIRMED.name(),
                decisionReasonCode != null ? decisionReasonCode : "duplicate_confirmed_memory", null);
    }

    private void moveToConflict(AiGroupMemoryCandidateEntity candidate,
                                AiGroupMemoryEntity confirmedMemory,
                                SemanticConflictAnalysis analysis,
                                LocalDateTime reviewedAt,
                                String decisionReasonCode) {
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
                "confirmed_memory_exists",
                decisionReasonCode != null ? decisionReasonCode : "confirmed_memory_exists",
                null,
                confirmedMemory.getId());
        memoryLifecycleMetricsService.recordConflict();
        memoryLifecycleAuditService.recordStateEvent("CANDIDATE", candidate.getId(), candidate.getCustomerId(),
                candidate.getGroupId(), candidate.getMemoryType(), "REVIEW_REQUIRED",
                CandidateStatus.CONFLICT.name(),
                decisionReasonCode != null ? decisionReasonCode : "confirmed_memory_exists", null);
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
            String decisionReasonCode,
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
        audit.setDecisionReasonCode(decisionReasonCode);
        audit.setRemarks(remarks);
        audit.setRelatedFormalId(relatedFormalId);
        reviewAuditMapper.insert(audit);
    }

    private ResolutionExecution resolveConfirmedMemoryExecution(AiGroupMemoryCandidateEntity candidate, String resolutionReason) {
        SemanticMemoryFingerprint fingerprint = conflictDetector.fingerprint(candidate.getMemoryType(), candidate.getMemoryContent());
        List<AiGroupMemoryEntity> confirmedMemories = confirmedMemoryMapper.selectByGroupIdAndCustomerId(
                candidate.getGroupId(), candidate.getCustomerId());
        ConfirmedMemoryMatch match = findConfirmedMemoryMatch(candidate.getMemoryType(), fingerprint, confirmedMemories);
        if (match == null) {
            return null;
        }

        MemoryConflictResolution resolution = memoryConflictResolver.resolve(
                candidate.getMemoryType(), fingerprint, candidate, match.memory());
        if (resolution.getStrategy() == MemoryConflictResolution.ResolutionStrategy.REQUIRE_REVIEW) {
            return null;
        }
        return new ResolutionExecution(match.memory(), resolution);
    }

    private void applyResolutionExecution(AiGroupMemoryCandidateEntity candidate,
                                          ResolutionExecution execution,
                                          String reviewer,
                                          String remarks) {
        AiGroupMemoryEntity targetMemory = execution.confirmedMemory();
        MemoryConflictResolution resolution = execution.resolution();
        switch (resolution.getStrategy()) {
            case ABSORB_EXISTING -> {
                resolveConflicts(candidate.getId(), "RESOLVED", resolution.getReasonCode(), remarks);
                candidateMapper.updateStatus(candidate.getId(), CandidateStatus.CONFIRMED.name(), reviewer, LocalDateTime.now(), targetMemory.getId());
                AiGroupMemoryEntity merged = memoryMergeService.mergeAsEquivalent(targetMemory, candidate, resolution.getReasonCode());
                confirmedMemoryMaintenanceService.refreshConfirmedMemory(merged);
                recordAudit(candidate, "AUTO_MERGE", CandidateStatus.CONFIRMED.name(), reviewer,
                        resolution.getReasonCode(), resolution.getReasonCode(), remarks, targetMemory.getId());
                memoryLifecycleMetricsService.recordPromotion();
            }
            case UPDATE_EXISTING_PREFERENCE -> {
                resolveConflicts(candidate.getId(), "RESOLVED", resolution.getReasonCode(), remarks);
                candidateMapper.updateStatus(candidate.getId(), CandidateStatus.CONFIRMED.name(), reviewer, LocalDateTime.now(), targetMemory.getId());
                AiGroupMemoryEntity merged = memoryMergeService.mergeAsLatestPreference(targetMemory, candidate, resolution.getReasonCode());
                confirmedMemoryMaintenanceService.refreshConfirmedMemory(merged);
                recordAudit(candidate, "UPSERT", CandidateStatus.CONFIRMED.name(), reviewer,
                        resolution.getReasonCode(), resolution.getReasonCode(), remarks, targetMemory.getId());
                memoryLifecycleMetricsService.recordPromotion();
            }
            case APPEND_AS_VERSION -> {
                Long confirmedMemoryId = insertConfirmedMemory(candidate, resolution.getReasonCode(), false);
                resolveConflicts(candidate.getId(), "RESOLVED", resolution.getReasonCode(), remarks);
                candidateMapper.updateStatus(candidate.getId(), CandidateStatus.CONFIRMED.name(), reviewer, LocalDateTime.now(), confirmedMemoryId);
                recordAudit(candidate, "APPEND", CandidateStatus.CONFIRMED.name(), reviewer,
                        resolution.getReasonCode(), resolution.getReasonCode(), remarks, confirmedMemoryId);
                memoryLifecycleMetricsService.recordPromotion();
            }
            case APPEND_WITH_TIME_WINDOW -> {
                Long confirmedMemoryId = insertConfirmedMemory(candidate, resolution.getReasonCode(), true);
                resolveConflicts(candidate.getId(), "RESOLVED", resolution.getReasonCode(), remarks);
                candidateMapper.updateStatus(candidate.getId(), CandidateStatus.CONFIRMED.name(), reviewer, LocalDateTime.now(), confirmedMemoryId);
                recordAudit(candidate, "APPEND", CandidateStatus.CONFIRMED.name(), reviewer,
                        resolution.getReasonCode(), resolution.getReasonCode(), remarks, confirmedMemoryId);
                memoryLifecycleMetricsService.recordPromotion();
            }
            case REQUIRE_REVIEW -> throw new IllegalStateException("ResolutionExecution should not require review");
            default -> throw new IllegalStateException("Unsupported resolution strategy: " + resolution.getStrategy());
        }
    }

    private void updateExistingConfirmedMemory(AiGroupMemoryCandidateEntity candidate,
                                               AiGroupMemoryEntity confirmedMemory,
                                               LocalDateTime reviewedAt,
                                               MemoryConflictResolution resolution) {
        resolveConflicts(candidate.getId(), "RESOLVED", resolution.getReasonCode(), null);
        candidateMapper.updateStatus(candidate.getId(), CandidateStatus.CONFIRMED.name(), "SYSTEM", reviewedAt, confirmedMemory.getId());
        AiGroupMemoryEntity merged = memoryMergeService.mergeAsLatestPreference(
                confirmedMemory, candidate, resolution.getReasonCode());
        confirmedMemoryMaintenanceService.refreshConfirmedMemory(merged);
        recordAudit(candidate, "UPSERT", CandidateStatus.CONFIRMED.name(), "SYSTEM",
                resolution.getReasonCode(), resolution.getReasonCode(), null, confirmedMemory.getId());
    }

    private void appendSymptomWithTimeWindow(AiGroupMemoryCandidateEntity candidate,
                                             LocalDateTime reviewedAt,
                                             MemoryConflictResolution resolution) {
        Long confirmedMemoryId = insertConfirmedMemory(candidate, resolution.getReasonCode(), true);
        resolveConflicts(candidate.getId(), "RESOLVED", resolution.getReasonCode(), null);
        candidateMapper.updateStatus(candidate.getId(), CandidateStatus.CONFIRMED.name(), "SYSTEM", reviewedAt, confirmedMemoryId);
        recordAudit(candidate, "APPEND", CandidateStatus.CONFIRMED.name(), "SYSTEM",
                resolution.getReasonCode(), resolution.getReasonCode(), null, confirmedMemoryId);
    }

    private Long insertConfirmedMemory(AiGroupMemoryCandidateEntity candidate,
                                       String decisionReasonCode,
                                       boolean symptomTimeWindow) {
        AiGroupMemoryEntity confirmed = new AiGroupMemoryEntity();
        confirmed.setCustomerId(candidate.getCustomerId());
        confirmed.setGroupId(candidate.getGroupId());
        confirmed.setMemoryType(candidate.getMemoryType());
        confirmed.setMemoryKey(candidate.getMemoryKey());
        confirmed.setMemoryContent(candidate.getMemoryContent());
        confirmed.setMemoryRepresentation("EVIDENCE");
        confirmed.setConfidenceScore(candidate.getConfidenceScore());
        confirmed.setSourceMessage(candidate.getSourceMessage());
        confirmed.setExtractedAt(candidate.getLastSeenAt() != null ? candidate.getLastSeenAt() : LocalDateTime.now());
        confirmed.setIsDeleted(0);
        if (symptomTimeWindow) {
            memoryMergeService.enrichAsSymptomWindow(confirmed, candidate, decisionReasonCode);
        } else {
            memoryMergeService.enrichAsVersionedFact(confirmed, candidate, decisionReasonCode);
        }
        memoryRetentionPolicy.applyDefaults(confirmed);

        confirmedMemoryMapper.insert(confirmed);
        Long confirmedMemoryId = confirmed.getId() != null ? confirmed.getId() : 100L;
        String embeddingId = vectorStoreService.saveGroupMemory(confirmed);
        confirmedMemoryMapper.updateEmbeddingId(confirmedMemoryId, embeddingId);

        if (redisMemoryIndexService != null) {
            redisMemoryIndexService.addToHashIndex(candidate.getGroupId(), candidate.getMemoryContent());
            redisMemoryIndexService.cacheRecentMemory(
                    candidate.getGroupId(), confirmedMemoryId, candidate.getMemoryContent(), System.currentTimeMillis());
        }
        return confirmedMemoryId;
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

    private record ResolutionExecution(
            AiGroupMemoryEntity confirmedMemory,
            MemoryConflictResolution resolution) {
    }
}
