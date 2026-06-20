package com.lx.governance.vectormemory.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;

@Service
public class MemoryMergeService {

    public AiGroupMemoryEntity mergeAsEquivalent(AiGroupMemoryEntity existing,
                                                 AiGroupMemoryCandidateEntity candidate,
                                                 String decisionReasonCode) {
        AiGroupMemoryEntity merged = copy(existing);
        merged.setConfidenceScore(max(existing.getConfidenceScore(), candidate.getConfidenceScore()));
        merged.setSourceCount((existing.getSourceCount() == null ? 1 : existing.getSourceCount()) + 1);
        merged.setEvidenceCount((existing.getEvidenceCount() == null ? 1 : existing.getEvidenceCount()) + 1);
        merged.setLastReinforcedAt(now(candidate));
        merged.setSourceMessage(candidate.getSourceMessage());
        merged.setDecisionReasonCode(decisionReasonCode);
        return merged;
    }

    public AiGroupMemoryEntity mergeAsLatestPreference(AiGroupMemoryEntity existing,
                                                       AiGroupMemoryCandidateEntity candidate,
                                                       String decisionReasonCode) {
        AiGroupMemoryEntity merged = copy(existing);
        merged.setMemoryContent(candidate.getMemoryContent());
        merged.setMemoryKey(candidate.getMemoryKey());
        merged.setConfidenceScore(candidate.getConfidenceScore());
        merged.setSourceMessage(candidate.getSourceMessage());
        merged.setExtractedAt(now(candidate));
        merged.setLastReinforcedAt(now(candidate));
        merged.setSourceCount((existing.getSourceCount() == null ? 1 : existing.getSourceCount()) + 1);
        merged.setEvidenceCount((existing.getEvidenceCount() == null ? 1 : existing.getEvidenceCount()) + 1);
        merged.setLifecycleStatus("CONFIRMED");
        merged.setRetentionPolicy("LATEST_WINS");
        merged.setDecisionReasonCode(decisionReasonCode);
        return merged;
    }

    public AiGroupMemoryEntity enrichAsVersionedFact(AiGroupMemoryEntity confirmed,
                                                     AiGroupMemoryCandidateEntity candidate,
                                                     String decisionReasonCode) {
        confirmed.setLifecycleStatus("CONFIRMED");
        confirmed.setRetentionPolicy(defaultRetentionPolicy(candidate.getMemoryType()));
        confirmed.setLastReinforcedAt(now(candidate));
        confirmed.setLastAccessedAt(now(candidate));
        confirmed.setSourceCount(candidate.getSourceCount() != null ? candidate.getSourceCount() : 1);
        confirmed.setEvidenceCount(candidate.getEvidenceCount() != null ? candidate.getEvidenceCount() : 1);
        confirmed.setDecisionReasonCode(decisionReasonCode);
        return confirmed;
    }

    public AiGroupMemoryEntity enrichAsSymptomWindow(AiGroupMemoryEntity confirmed,
                                                     AiGroupMemoryCandidateEntity candidate,
                                                     String decisionReasonCode) {
        enrichAsVersionedFact(confirmed, candidate, decisionReasonCode);
        confirmed.setRetentionPolicy("TIME_WINDOW");
        LocalDateTime baseTime = candidate.getLastSeenAt() != null ? candidate.getLastSeenAt() : LocalDateTime.now();
        confirmed.setExpiresAt(baseTime.plusDays(30));
        return confirmed;
    }

    public String defaultRetentionPolicy(String memoryType) {
        if ("SYMPTOM".equalsIgnoreCase(memoryType)) {
            return "TIME_WINDOW";
        }
        if ("PREFERENCE".equalsIgnoreCase(memoryType) || "DIET".equalsIgnoreCase(memoryType)) {
            return "LATEST_WINS";
        }
        return "LONG_TERM";
    }

    private AiGroupMemoryEntity copy(AiGroupMemoryEntity existing) {
        AiGroupMemoryEntity copy = new AiGroupMemoryEntity();
        copy.setId(existing.getId());
        copy.setCustomerId(existing.getCustomerId());
        copy.setGroupId(existing.getGroupId());
        copy.setMemoryType(existing.getMemoryType());
        copy.setMemoryKey(existing.getMemoryKey());
        copy.setMemoryContent(existing.getMemoryContent());
        copy.setMemoryRepresentation(existing.getMemoryRepresentation());
        copy.setSummaryGroupKey(existing.getSummaryGroupKey());
        copy.setSummaryOfMemoryId(existing.getSummaryOfMemoryId());
        copy.setLifecycleStatus(existing.getLifecycleStatus());
        copy.setRetentionPolicy(existing.getRetentionPolicy());
        copy.setExpiresAt(existing.getExpiresAt());
        copy.setDecayScore(existing.getDecayScore());
        copy.setLastAccessedAt(existing.getLastAccessedAt());
        copy.setLastReinforcedAt(existing.getLastReinforcedAt());
        copy.setSourceCount(existing.getSourceCount());
        copy.setEvidenceCount(existing.getEvidenceCount());
        copy.setConflictStatus(existing.getConflictStatus());
        copy.setSupersededBy(existing.getSupersededBy());
        copy.setArchivedAt(existing.getArchivedAt());
        copy.setArchiveReason(existing.getArchiveReason());
        copy.setDecisionReasonCode(existing.getDecisionReasonCode());
        copy.setEmbeddingId(existing.getEmbeddingId());
        copy.setConfidenceScore(existing.getConfidenceScore());
        copy.setSourceMessage(existing.getSourceMessage());
        copy.setExtractedAt(existing.getExtractedAt());
        copy.setIsDeleted(existing.getIsDeleted());
        return copy;
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

    private LocalDateTime now(AiGroupMemoryCandidateEntity candidate) {
        return candidate.getLastSeenAt() != null ? candidate.getLastSeenAt() : LocalDateTime.now();
    }
}
