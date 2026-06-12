package com.lx.governance.vectormemory.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryConflictEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryCandidateMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryConflictMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CandidateMemoryService {

    private static final int AUTO_CONFIRM_HIT_THRESHOLD = 5;
    private static final double AUTO_CONFIRM_CONFIDENCE_THRESHOLD = 0.8;

    private final AiGroupMemoryCandidateMapper candidateMapper;
    private final AiGroupMemoryConflictMapper conflictMapper;
    private final AiGroupMemoryMapper confirmedMemoryMapper;
    private final VectorStoreService vectorStoreService;
    private final RedisMemoryIndexService redisMemoryIndexService;

    public void processCandidate(String groupId,
                                 String customerId,
                                 MemoryExtractionResult.ExtractedMemory extracted,
                                 String sourceMessage) {
        String normalizedKey = normalizeKey(extracted.getContent());
        String normalizedValue = normalizeValue(extracted.getContent());
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
        boolean hasConflict = activeCandidates.stream().anyMatch(candidate ->
                candidate.getNormalizedKey() != null
                        && isConflict(normalizedKey, normalizedValue, candidate.getNormalizedKey(), candidate.getNormalizedValue()));

        AiGroupMemoryCandidateEntity entity = new AiGroupMemoryCandidateEntity();
        entity.setCustomerId(customerId);
        entity.setGroupId(groupId);
        entity.setMemoryType(mapCategoryToType(extracted.getCategory()));
        entity.setMemoryKey(extractMemoryKey(extracted.getContent()));
        entity.setNormalizedKey(normalizedKey);
        entity.setNormalizedValue(normalizedValue);
        entity.setPolarity(detectPolarity(extracted.getContent()));
        entity.setMemoryContent(extracted.getContent());
        entity.setConfidenceScore(BigDecimal.valueOf(extracted.getConfidence()));
        entity.setStatus(hasConflict ? "CONFLICT" : "CANDIDATE");
        entity.setHitCount(1);
        entity.setSourceMessage(sourceMessage);
        entity.setLastSeenAt(now);

        candidateMapper.insert(entity);

        if (hasConflict) {
            AiGroupMemoryCandidateEntity conflictTarget = activeCandidates.stream()
                    .filter(candidate -> candidate.getNormalizedKey() != null
                            && isConflict(normalizedKey, normalizedValue, candidate.getNormalizedKey(), candidate.getNormalizedValue()))
                    .findFirst()
                    .orElse(null);

            if (conflictTarget != null) {
                AiGroupMemoryConflictEntity conflict = new AiGroupMemoryConflictEntity();
                conflict.setCustomerId(customerId);
                conflict.setGroupId(groupId);
                conflict.setCandidateId(entity.getId());
                conflict.setConflictTargetType("CANDIDATE");
                conflict.setConflictTargetId(conflictTarget.getId());
                conflict.setConflictReason("normalized_value_conflict");
                conflict.setConflictScore(BigDecimal.valueOf(0.95));
                conflict.setResolutionStatus("OPEN");
                conflictMapper.insert(conflict);
            }
        }
    }

    public void confirmCandidate(Long candidateId, String reviewer) {
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

        resolveConflicts(candidateId, "RESOLVED");
        candidateMapper.updateStatus(candidateId, "CONFIRMED", reviewer, LocalDateTime.now(), confirmedMemoryId);
    }

    public void rejectCandidate(Long candidateId, String reviewer) {
        resolveConflicts(candidateId, "REJECTED");
        candidateMapper.updateStatus(candidateId, "REJECTED", reviewer, LocalDateTime.now(), null);
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

    private String normalizeKey(String content) {
        return extractMemoryKey(content);
    }

    private String normalizeValue(String content) {
        return content == null ? "" : content.trim();
    }

    private String detectPolarity(String content) {
        if (content == null) {
            return "NEUTRAL";
        }
        if (content.contains("不") || content.contains("无")) {
            return "NEGATIVE";
        }
        return "POSITIVE";
    }

    private boolean isConflict(String key1, String value1, String key2, String value2) {
        if (key1 == null || key2 == null || value1 == null || value2 == null) {
            return false;
        }
        if (!key1.substring(0, Math.min(4, key1.length()))
                .equals(key2.substring(0, Math.min(4, key2.length())))) {
            return false;
        }
        return !value1.equals(value2);
    }

    private void resolveConflicts(Long candidateId, String resolutionStatus) {
        List<AiGroupMemoryConflictEntity> conflicts = conflictMapper.selectByCandidateId(candidateId);
        for (AiGroupMemoryConflictEntity conflict : conflicts) {
            if ("OPEN".equalsIgnoreCase(conflict.getResolutionStatus())) {
                conflictMapper.updateResolutionStatus(conflict.getId(), resolutionStatus);
            }
        }
    }
}
