package com.lx.governance.aichat.service;

import com.lx.governance.aichat.dto.StructuredMemoryQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class StructuredMemoryQueryValidator {

    // 这些字段是当前检索层真正能解释的元数据白名单。
    // LLM 可以自由理解用户问题，但只能在这些字段上构造过滤表达式。
    private static final Set<String> ALLOWED_MEMORY_TYPES = Set.of(
            "SYMPTOM", "ALLERGY", "MEDICATION", "DIET", "PREFERENCE", "OTHER"
    );
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "memoryType", "memoryTypes", "memoryKey", "memoryContent", "sourceMessage",
            "confidenceScore", "minConfidence", "verifiedOnly", "recentDays"
    );
    private static final Set<String> ALLOWED_OPS = Set.of("EQ", "IN", "GTE", "LTE", "CONTAINS");

    public StructuredMemoryQuery validate(StructuredMemoryQuery query) {
        if (query == null) {
            return StructuredMemoryQuery.builder()
                    .semanticQuery("")
                    .filter(StructuredMemoryQuery.MemoryFilter.builder().build())
                    .build();
        }

        StructuredMemoryQuery.MemoryFilter filter = query.getFilter();
        if (filter == null) {
            filter = StructuredMemoryQuery.MemoryFilter.builder().build();
        }

        Set<String> sanitizedTypes = new LinkedHashSet<>();
        if (filter.getMemoryTypes() != null) {
            for (String type : filter.getMemoryTypes()) {
                if (type != null && ALLOWED_MEMORY_TYPES.contains(type.trim().toUpperCase())) {
                    sanitizedTypes.add(type.trim().toUpperCase());
                }
            }
        }

        Double minConfidence = clamp(filter.getMinConfidence(), 0.0, 1.0);
        Integer recentDays = clampInt(filter.getRecentDays(), 1, 365);
        StructuredMemoryQuery.FilterExpression filterExpression = sanitizeExpression(query.getFilterExpression());

        if ((filterExpression == null
                || (filterExpression.getClauses().isEmpty() && filterExpression.getExpressions().isEmpty()))
                && (!sanitizedTypes.isEmpty() || minConfidence != null || filter.getVerifiedOnly() != null || recentDays != null)) {
            filterExpression = buildExpressionFromFixedFilter(sanitizedTypes, minConfidence, filter.getVerifiedOnly(), recentDays);
        }

        return StructuredMemoryQuery.builder()
                .originalMessage(query.getOriginalMessage())
                .semanticQuery(normalize(query.getSemanticQuery()))
                .keywords(query.getKeywords() != null ? query.getKeywords() : java.util.List.of())
                .filter(StructuredMemoryQuery.MemoryFilter.builder()
                        .memoryTypes(sanitizedTypes)
                        .minConfidence(minConfidence)
                        .verifiedOnly(filter.getVerifiedOnly())
                        .recentDays(recentDays)
                        .build())
                .filterExpression(filterExpression)
                .planSource(query.getPlanSource())
                .planReason(query.getPlanReason())
                .contextDependent(query.getContextDependent())
                .build();
    }

    private StructuredMemoryQuery.FilterExpression sanitizeExpression(StructuredMemoryQuery.FilterExpression expression) {
        if (expression == null) {
            return null;
        }

        // 递归清洗表达式树：
        // - 过滤掉非法 field/op
        // - 规范化 value
        // - 保留仍然有意义的子表达式
        List<StructuredMemoryQuery.FilterClause> sanitizedClauses = new ArrayList<>();
        for (StructuredMemoryQuery.FilterClause clause : expression.getClauses() != null ? expression.getClauses() : java.util.List.<StructuredMemoryQuery.FilterClause>of()) {
            if (clause == null) {
                continue;
            }
            String field = normalize(clause.getField());
            String op = normalize(clause.getOp()).toUpperCase();
            List<String> values = clause.getValues() != null ? clause.getValues() : java.util.List.of();

            if (!isAllowedField(field) || !isAllowedOp(op) || values.isEmpty()) {
                continue;
            }

            List<String> sanitizedValues = sanitizeClauseValues(field, values);
            if (sanitizedValues.isEmpty()) {
                continue;
            }
            sanitizedClauses.add(StructuredMemoryQuery.FilterClause.builder()
                    .field(field)
                    .op(op)
                    .values(sanitizedValues)
                    .build());
        }

        List<StructuredMemoryQuery.FilterExpression> sanitizedChildren = new ArrayList<>();
        for (StructuredMemoryQuery.FilterExpression child : expression.getExpressions() != null
                ? expression.getExpressions()
                : java.util.List.<StructuredMemoryQuery.FilterExpression>of()) {
            StructuredMemoryQuery.FilterExpression sanitizedChild = sanitizeExpression(child);
            if (sanitizedChild != null) {
                sanitizedChildren.add(sanitizedChild);
            }
        }

        if (sanitizedClauses.isEmpty() && sanitizedChildren.isEmpty()) {
            return null;
        }
        String operator = normalize(expression.getOperator()).toUpperCase();
        if (!"OR".equals(operator)) {
            operator = "AND";
        }
        return StructuredMemoryQuery.FilterExpression.builder()
                .operator(operator)
                .clauses(sanitizedClauses)
                .expressions(sanitizedChildren)
                .build();
    }

    private StructuredMemoryQuery.FilterExpression buildExpressionFromFixedFilter(
            Set<String> memoryTypes,
            Double minConfidence,
            Boolean verifiedOnly,
            Integer recentDays) {
        List<StructuredMemoryQuery.FilterClause> clauses = new ArrayList<>();
        if (memoryTypes != null && !memoryTypes.isEmpty()) {
            clauses.add(StructuredMemoryQuery.FilterClause.builder()
                    .field("memoryType")
                    .op("IN")
                    .values(new ArrayList<>(memoryTypes))
                    .build());
        }
        if (minConfidence != null) {
            clauses.add(StructuredMemoryQuery.FilterClause.builder()
                    .field("confidenceScore")
                    .op("GTE")
                    .values(List.of(String.valueOf(minConfidence)))
                    .build());
        }
        if (verifiedOnly != null) {
            clauses.add(StructuredMemoryQuery.FilterClause.builder()
                    .field("verifiedOnly")
                    .op("EQ")
                    .values(List.of(String.valueOf(verifiedOnly)))
                    .build());
        }
        if (recentDays != null) {
            clauses.add(StructuredMemoryQuery.FilterClause.builder()
                    .field("recentDays")
                    .op("LTE")
                    .values(List.of(String.valueOf(recentDays)))
                    .build());
        }
        return StructuredMemoryQuery.FilterExpression.builder()
                .operator("AND")
                .clauses(clauses)
                .build();
    }

    private boolean isAllowedField(String field) {
        return ALLOWED_FIELDS.contains(field);
    }

    private boolean isAllowedOp(String op) {
        return ALLOWED_OPS.contains(op);
    }

    private List<String> sanitizeClauseValues(String field, List<String> values) {
        List<String> sanitized = new ArrayList<>();
        for (String value : values) {
            String normalizedValue = normalize(value);
            if (normalizedValue.isEmpty()) {
                continue;
            }
            switch (field) {
                case "memoryTypes", "memoryType" -> {
                    String upper = normalizedValue.toUpperCase();
                    if (ALLOWED_MEMORY_TYPES.contains(upper)) {
                        sanitized.add(upper);
                    }
                }
                case "minConfidence", "confidenceScore" -> {
                    try {
                        sanitized.add(String.valueOf(clamp(Double.parseDouble(normalizedValue), 0.0, 1.0)));
                    } catch (NumberFormatException ignored) {
                    }
                }
                case "verifiedOnly" -> {
                    if ("true".equalsIgnoreCase(normalizedValue) || "false".equalsIgnoreCase(normalizedValue)) {
                        sanitized.add(String.valueOf(Boolean.parseBoolean(normalizedValue)));
                    }
                }
                case "recentDays" -> {
                    try {
                        sanitized.add(String.valueOf(clampInt(Integer.parseInt(normalizedValue), 1, 365)));
                    } catch (NumberFormatException ignored) {
                    }
                }
                case "memoryKey", "memoryContent", "sourceMessage" -> sanitized.add(normalizedValue);
                default -> {
                }
            }
        }
        return sanitized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private Double clamp(Double value, double min, double max) {
        if (value == null) {
            return null;
        }
        return Math.max(min, Math.min(max, value));
    }

    private Integer clampInt(Integer value, int min, int max) {
        if (value == null) {
            return null;
        }
        return Math.max(min, Math.min(max, value));
    }
}
