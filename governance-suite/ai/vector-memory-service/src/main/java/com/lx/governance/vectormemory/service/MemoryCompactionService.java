package com.lx.governance.vectormemory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.config.MemoryLifecycleProperties;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryCompactionService {

    private final AiGroupMemoryMapper memoryMapper;
    private final SemanticMemoryConflictDetector conflictDetector;
    private final ConfirmedMemoryMaintenanceService confirmedMemoryMaintenanceService;
    private final VectorStoreService vectorStoreService;
    private final MemoryRetentionPolicy memoryRetentionPolicy;
    private final MemoryLifecycleProperties lifecycleProperties;
    private final MemoryLifecycleMetricsService memoryLifecycleMetricsService;
    private final MemoryLifecycleAuditService memoryLifecycleAuditService;

    public CompactionBatchResult compactCandidates(int limit) {
        List<AiGroupMemoryEntity> candidates = memoryMapper.selectCompactionCandidates(limit);
        if (candidates == null || candidates.isEmpty()) {
            return new CompactionBatchResult(0, 0, 0);
        }

        Map<String, List<AiGroupMemoryEntity>> grouped = candidates.stream()
                .collect(Collectors.groupingBy(this::buildCompactionGroupKey, LinkedHashMap::new, Collectors.toList()));

        int created = 0;
        int updated = 0;
        int evidenceLinked = 0;

        for (Map.Entry<String, List<AiGroupMemoryEntity>> entry : grouped.entrySet()) {
            List<AiGroupMemoryEntity> memories = entry.getValue();
            if (!shouldCompact(memories)) {
                continue;
            }

            AiGroupMemoryEntity representative = pickRepresentative(memories);
            String summaryGroupKey = entry.getKey();
            String summaryContent = buildSummaryContent(memories);

            evidenceLinked += markEvidenceMemories(memories, summaryGroupKey, representative.getId());

            AiGroupMemoryEntity existingSummary = memoryMapper.selectSummaryByGroupKey(
                    representative.getGroupId(), representative.getCustomerId(), summaryGroupKey);
            if (existingSummary == null) {
                insertSummaryMemory(representative, summaryGroupKey, representative.getId(), summaryContent, memories);
                created++;
                memoryLifecycleMetricsService.recordCompaction();
            } else {
                updateSummaryMemory(existingSummary, summaryGroupKey, representative.getId(), summaryContent, memories);
                updated++;
            }
        }

        return new CompactionBatchResult(created, updated, evidenceLinked);
    }

    private boolean shouldCompact(List<AiGroupMemoryEntity> memories) {
        if (memories == null || memories.isEmpty()) {
            return false;
        }
        if (memories.size() >= 2) {
            return true;
        }
        AiGroupMemoryEntity memory = memories.get(0);
        int evidenceCount = memory.getEvidenceCount() == null ? 0 : memory.getEvidenceCount();
        int sourceCount = memory.getSourceCount() == null ? 0 : memory.getSourceCount();
        if (evidenceCount >= lifecycleProperties.getCompaction().getMinEvidenceCount()
                || sourceCount >= lifecycleProperties.getCompaction().getMinSourceCount()) {
            return true;
        }
        if ("OTHER".equalsIgnoreCase(memory.getMemoryType()) && memory.getExtractedAt() != null) {
            return memory.getExtractedAt().isBefore(LocalDateTime.now().minusDays(
                    lifecycleProperties.getCompaction().getLongCycleEventDays()));
        }
        return false;
    }

    private int markEvidenceMemories(List<AiGroupMemoryEntity> memories, String summaryGroupKey, Long representativeId) {
        int updated = 0;
        for (AiGroupMemoryEntity memory : memories) {
            memoryMapper.updateCompactionMetadata(
                    memory.getId(),
                    "EVIDENCE",
                    summaryGroupKey,
                    representativeId,
                    "compaction_evidence_linked");
            updated++;
        }
        return updated;
    }

    private void insertSummaryMemory(AiGroupMemoryEntity representative,
                                     String summaryGroupKey,
                                     Long representativeId,
                                     String summaryContent,
                                     List<AiGroupMemoryEntity> evidenceMemories) {
        AiGroupMemoryEntity summary = new AiGroupMemoryEntity();
        summary.setCustomerId(representative.getCustomerId());
        summary.setGroupId(representative.getGroupId());
        summary.setMemoryType(representative.getMemoryType());
        summary.setMemoryKey(representative.getMemoryKey());
        summary.setMemoryContent(summaryContent);
        summary.setMemoryRepresentation("SUMMARY");
        summary.setSummaryGroupKey(summaryGroupKey);
        summary.setSummaryOfMemoryId(representativeId);
        summary.setLifecycleStatus("CONFIRMED");
        summary.setConfidenceScore(averageConfidence(evidenceMemories));
        summary.setSourceMessage("summary_memory");
        summary.setExtractedAt(LocalDateTime.now());
        summary.setLastReinforcedAt(LocalDateTime.now());
        summary.setLastAccessedAt(LocalDateTime.now());
        summary.setSourceCount(totalSourceCount(evidenceMemories));
        summary.setEvidenceCount(evidenceMemories.size());
        summary.setDecisionReasonCode("memory_compaction_summary_created");
        summary.setIsDeleted(0);
        memoryRetentionPolicy.applyDefaults(summary);

        memoryMapper.insert(summary);
        memoryLifecycleAuditService.recordStateEvent("COMPACTION", summary.getId(), summary.getCustomerId(), summary.getGroupId(),
                summary.getMemoryType(), "SUMMARY_CREATED", summary.getLifecycleStatus(),
                "memory_compaction_summary_created", summaryGroupKey);
        Long memoryId = summary.getId() != null ? summary.getId() : 100L;
        String embeddingId = vectorStoreService.saveGroupMemory(summary);
        memoryMapper.updateEmbeddingId(memoryId, embeddingId);
    }

    private void updateSummaryMemory(AiGroupMemoryEntity existingSummary,
                                     String summaryGroupKey,
                                     Long representativeId,
                                     String summaryContent,
                                     List<AiGroupMemoryEntity> evidenceMemories) {
        existingSummary.setMemoryContent(summaryContent);
        existingSummary.setMemoryRepresentation("SUMMARY");
        existingSummary.setSummaryGroupKey(summaryGroupKey);
        existingSummary.setSummaryOfMemoryId(representativeId);
        existingSummary.setSourceCount(totalSourceCount(evidenceMemories));
        existingSummary.setEvidenceCount(evidenceMemories.size());
        existingSummary.setLastReinforcedAt(LocalDateTime.now());
        existingSummary.setLastAccessedAt(LocalDateTime.now());
        existingSummary.setConfidenceScore(averageConfidence(evidenceMemories));
        existingSummary.setDecisionReasonCode("memory_compaction_summary_updated");
        memoryRetentionPolicy.applyDefaults(existingSummary);
        memoryLifecycleAuditService.recordStateEvent("COMPACTION", existingSummary.getId(), existingSummary.getCustomerId(),
                existingSummary.getGroupId(), existingSummary.getMemoryType(), "SUMMARY_UPDATED",
                existingSummary.getLifecycleStatus(), "memory_compaction_summary_updated", summaryGroupKey);
        confirmedMemoryMaintenanceService.refreshConfirmedMemory(existingSummary);
    }

    private AiGroupMemoryEntity pickRepresentative(List<AiGroupMemoryEntity> memories) {
        return memories.stream()
                .max(Comparator
                        .comparing((AiGroupMemoryEntity memory) -> memory.getConfidenceScore() != null
                                ? memory.getConfidenceScore() : BigDecimal.ZERO)
                        .thenComparing(memory -> memory.getExtractedAt() != null ? memory.getExtractedAt() : LocalDateTime.MIN))
                .orElse(memories.get(0));
    }

    private String buildSummaryContent(List<AiGroupMemoryEntity> memories) {
        List<String> uniqueContents = new ArrayList<>();
        for (AiGroupMemoryEntity memory : memories) {
            String content = memory.getMemoryContent();
            if (content != null && !content.isBlank() && !uniqueContents.contains(content)) {
                uniqueContents.add(content);
            }
        }
        String summaryBody = uniqueContents.stream().limit(3).collect(Collectors.joining("；"));
        if (summaryBody.isBlank()) {
            summaryBody = memories.get(0).getMemoryContent();
        }
        return "摘要记忆：" + summaryBody;
    }

    private String buildCompactionGroupKey(AiGroupMemoryEntity memory) {
        SemanticMemoryFingerprint fingerprint = conflictDetector.fingerprint(memory.getMemoryType(), memory.getMemoryContent());
        return memory.getCustomerId() + "|" + memory.getGroupId() + "|" + fingerprint.getNormalizedKey();
    }

    private BigDecimal averageConfidence(List<AiGroupMemoryEntity> memories) {
        double average = memories.stream()
                .map(AiGroupMemoryEntity::getConfidenceScore)
                .filter(value -> value != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.8d);
        return BigDecimal.valueOf(average).setScale(3, RoundingMode.HALF_UP);
    }

    private int totalSourceCount(List<AiGroupMemoryEntity> memories) {
        return memories.stream()
                .map(AiGroupMemoryEntity::getSourceCount)
                .filter(value -> value != null)
                .mapToInt(Integer::intValue)
                .sum();
    }

    public record CompactionBatchResult(int created, int updated, int evidenceLinked) {
    }
}
