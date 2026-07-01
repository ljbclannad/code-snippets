package com.lx.governance.vectormemory.domain.candidate;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.domain.candidate.MemoryConflictResolution.ResolutionStrategy;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemoryConflictResolver {

    private final SemanticMemoryConflictDetector conflictDetector;

    public MemoryConflictResolution resolve(String memoryType,
                                            SemanticMemoryFingerprint candidateFingerprint,
                                            AiGroupMemoryCandidateEntity candidate,
                                            AiGroupMemoryEntity confirmedMemory) {
        SemanticMemoryFingerprint confirmedFingerprint = conflictDetector.fingerprint(
                confirmedMemory.getMemoryType(), confirmedMemory.getMemoryContent());
        SemanticConflictAnalysis analysis = conflictDetector.analyze(candidateFingerprint, confirmedFingerprint);

        if (analysis.isDuplicate()) {
            return resolution(MemoryRelationType.EXACT_EQUIVALENT, ResolutionStrategy.ABSORB_EXISTING,
                    "semantic_exact_duplicate", "candidate is equivalent to existing confirmed memory", analysis.getConflictScore());
        }

        if (isApproxEquivalent(candidateFingerprint, confirmedFingerprint)) {
            return resolution(MemoryRelationType.APPROX_EQUIVALENT, ResolutionStrategy.ABSORB_EXISTING,
                    "semantic_approx_equivalent", "candidate is close enough to merge into existing memory", 0.88d);
        }

        if (analysis.isConflict()) {
            if (isPreferenceType(memoryType)) {
                return resolution(MemoryRelationType.CONFLICT, ResolutionStrategy.UPDATE_EXISTING_PREFERENCE,
                        "preference_latest_wins", "preference memory should update latest value", analysis.getConflictScore());
            }
            if (isFactType(memoryType)) {
                return resolution(MemoryRelationType.CONFLICT, ResolutionStrategy.APPEND_AS_VERSION,
                        "fact_version_append", "fact memory should preserve historical versions and sources", analysis.getConflictScore());
            }
            if (isSymptomType(memoryType)) {
                return resolution(MemoryRelationType.COEXIST, ResolutionStrategy.APPEND_WITH_TIME_WINDOW,
                        "symptom_time_window_append", "symptom memory can coexist with time-window retention", analysis.getConflictScore());
            }
            return resolution(MemoryRelationType.CONFLICT, ResolutionStrategy.REQUIRE_REVIEW,
                    analysis.getConflictReason(), "conflict requires reviewer decision", analysis.getConflictScore());
        }

        return resolution(MemoryRelationType.COEXIST, ResolutionStrategy.APPEND_AS_VERSION,
                "semantic_coexist", "memories can coexist in the same lifecycle family", 0.60d);
    }

    private boolean isApproxEquivalent(SemanticMemoryFingerprint left, SemanticMemoryFingerprint right) {
        if (left == null || right == null) {
            return false;
        }
        if (!safeEquals(left.getNormalizedKey(), right.getNormalizedKey())) {
            return false;
        }
        if (!safeEquals(left.getPolarity(), right.getPolarity())) {
            return false;
        }
        String leftValue = safe(left.getNormalizedValue());
        String rightValue = safe(right.getNormalizedValue());
        return !leftValue.isBlank()
                && !rightValue.isBlank()
                && (leftValue.contains(rightValue) || rightValue.contains(leftValue));
    }

    private boolean isPreferenceType(String memoryType) {
        return "PREFERENCE".equalsIgnoreCase(memoryType) || "DIET".equalsIgnoreCase(memoryType);
    }

    private boolean isFactType(String memoryType) {
        return "ALLERGY".equalsIgnoreCase(memoryType)
                || "MEDICATION".equalsIgnoreCase(memoryType)
                || "OTHER".equalsIgnoreCase(memoryType);
    }

    private boolean isSymptomType(String memoryType) {
        return "SYMPTOM".equalsIgnoreCase(memoryType);
    }

    private MemoryConflictResolution resolution(MemoryRelationType relationType,
                                                ResolutionStrategy strategy,
                                                String reasonCode,
                                                String summary,
                                                double score) {
        return MemoryConflictResolution.builder()
                .relationType(relationType)
                .strategy(strategy)
                .reasonCode(reasonCode)
                .summary(summary)
                .score(score)
                .build();
    }

    private boolean safeEquals(String left, String right) {
        return safe(left).equals(safe(right));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
