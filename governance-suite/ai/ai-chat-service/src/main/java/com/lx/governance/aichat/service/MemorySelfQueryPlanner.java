package com.lx.governance.aichat.service;

import com.lx.governance.aichat.dto.RetrievalQuery;
import com.lx.governance.aichat.dto.StructuredMemoryQuery;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class MemorySelfQueryPlanner {

    private final StructuredMemoryQueryValidator validator;

    public MemorySelfQueryPlanner(StructuredMemoryQueryValidator validator) {
        this.validator = validator;
    }

    public StructuredMemoryQuery plan(
            String originalMessage,
            RetrievalQuery retrievalQuery,
            IntentMemoryFilterStrategy.MemoryFilterDecision baseDecision) {

        Set<String> mergedMemoryTypes = mergeMemoryTypes(
                baseDecision != null ? baseDecision.getMemoryTypes() : null,
                retrievalQuery != null ? retrievalQuery.getSuggestedMemoryTypes() : null);

        Integer mergedRecentDays = mergeRecentDays(
                baseDecision != null ? baseDecision.getRecentDays() : null,
                retrievalQuery != null ? retrievalQuery.getSuggestedRecentDays() : null,
                retrievalQuery != null && Boolean.TRUE.equals(retrievalQuery.getContextDependent()));

        StructuredMemoryQuery rawQuery = StructuredMemoryQuery.builder()
                .originalMessage(originalMessage)
                .semanticQuery(retrievalQuery != null ? retrievalQuery.getRewrittenQuery() : originalMessage)
                .keywords(retrievalQuery != null ? retrievalQuery.getKeywords() : java.util.List.of())
                .filter(StructuredMemoryQuery.MemoryFilter.builder()
                        .memoryTypes(mergedMemoryTypes)
                        .minConfidence(mergeMinConfidence(
                                baseDecision != null ? baseDecision.getMinConfidence() : null,
                                retrievalQuery != null ? retrievalQuery.getSuggestedMinConfidence() : null))
                        .verifiedOnly(mergeVerifiedOnly(
                                baseDecision != null ? baseDecision.getVerifiedOnly() : null,
                                retrievalQuery != null ? retrievalQuery.getSuggestedVerifiedOnly() : null))
                        .recentDays(mergedRecentDays)
                        .build())
                .filterExpression(buildExpression(
                        mergedMemoryTypes,
                        mergeMinConfidence(
                                baseDecision != null ? baseDecision.getMinConfidence() : null,
                                retrievalQuery != null ? retrievalQuery.getSuggestedMinConfidence() : null),
                        mergeVerifiedOnly(
                                baseDecision != null ? baseDecision.getVerifiedOnly() : null,
                                retrievalQuery != null ? retrievalQuery.getSuggestedVerifiedOnly() : null),
                        mergedRecentDays))
                .planSource(retrievalQuery != null ? retrievalQuery.getRewriteSource() : "BYPASS")
                .planReason(retrievalQuery != null ? retrievalQuery.getRewriteReason() : "no_rewrite")
                .contextDependent(retrievalQuery != null ? retrievalQuery.getContextDependent() : null)
                .build();

        return validator.validate(rawQuery);
    }

    private Set<String> mergeMemoryTypes(Set<String> base, Set<String> suggested) {
        if (suggested == null || suggested.isEmpty()) {
            return base != null ? new LinkedHashSet<>(base) : new LinkedHashSet<>();
        }
        if (base == null || base.isEmpty()) {
            return new LinkedHashSet<>(suggested);
        }

        Set<String> intersection = new LinkedHashSet<>(base);
        intersection.retainAll(suggested);
        return intersection.isEmpty() ? new LinkedHashSet<>(base) : intersection;
    }

    private Integer mergeRecentDays(Integer base, Integer suggested, boolean contextDependent) {
        if (suggested == null || suggested <= 0) {
            suggested = null;
        }

        Integer merged;
        if (base == null || base <= 0) {
            merged = suggested;
        } else if (suggested == null) {
            merged = base;
        } else {
            merged = Math.min(base, suggested);
        }

        if (contextDependent) {
            if (merged == null || merged <= 0) {
                return 30;
            }
            return Math.min(merged, 30);
        }
        return merged;
    }

    private Double mergeMinConfidence(Double base, Double suggested) {
        if (suggested == null) {
            return base;
        }
        if (base == null) {
            return suggested;
        }
        return Math.max(base, suggested);
    }

    private Boolean mergeVerifiedOnly(Boolean base, Boolean suggested) {
        if (Boolean.TRUE.equals(base) || Boolean.TRUE.equals(suggested)) {
            return true;
        }
        if (base != null) {
            return base;
        }
        return suggested;
    }

    private StructuredMemoryQuery.FilterExpression buildExpression(
            Set<String> memoryTypes,
            Double minConfidence,
            Boolean verifiedOnly,
            Integer recentDays) {
        // Planner 默认生成一个扁平 AND 表达式，
        // 这样规则策略和 LLM 建议合并后的结果可以统一走 DSL/Compiler 路径。
        java.util.List<StructuredMemoryQuery.FilterClause> clauses = new java.util.ArrayList<>();
        if (memoryTypes != null && !memoryTypes.isEmpty()) {
            clauses.add(StructuredMemoryQuery.FilterClause.builder()
                    .field("memoryType")
                    .op("IN")
                    .values(new java.util.ArrayList<>(memoryTypes))
                    .build());
        }
        if (minConfidence != null) {
            clauses.add(StructuredMemoryQuery.FilterClause.builder()
                    .field("confidenceScore")
                    .op("GTE")
                    .values(java.util.List.of(String.valueOf(minConfidence)))
                    .build());
        }
        if (verifiedOnly != null) {
            clauses.add(StructuredMemoryQuery.FilterClause.builder()
                    .field("verifiedOnly")
                    .op("EQ")
                    .values(java.util.List.of(String.valueOf(verifiedOnly)))
                    .build());
        }
        if (recentDays != null) {
            clauses.add(StructuredMemoryQuery.FilterClause.builder()
                    .field("recentDays")
                    .op("LTE")
                    .values(java.util.List.of(String.valueOf(recentDays)))
                    .build());
        }
        return StructuredMemoryQuery.FilterExpression.builder()
                .operator("AND")
                .clauses(clauses)
                .build();
    }
}
