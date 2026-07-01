package com.lx.governance.vectormemory.domain.retrieval;

import com.lx.governance.vectormemory.domain.lifecycle.MemoryTypeNormalizer;

import com.lx.governance.vectormemory.dto.MemorySearchFilter;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class StructuredMemoryFilterMatcher {

    private final MemoryTypeNormalizer memoryTypeNormalizer;

    /**
     * 递归执行编译后的表达式树。
     * SQL 仍然负责一部分基础过滤，这里负责补齐 OR / 嵌套 / 文本包含等更复杂的运行时判断。
     */
    public boolean matches(AiGroupMemoryEntity entity, MemorySearchFilter.Expression expression) {
        if (expression == null) {
            return true;
        }

        boolean isOr = "OR".equalsIgnoreCase(expression.getOperator());
        List<MemorySearchFilter.Condition> conditions = expression.getConditions() != null
                ? expression.getConditions()
                : List.of();
        List<MemorySearchFilter.Expression> children = expression.getExpressions() != null
                ? expression.getExpressions()
                : List.of();

        if (isOr) {
            for (MemorySearchFilter.Condition condition : conditions) {
                if (matchesCondition(entity, condition)) {
                    return true;
                }
            }
            for (MemorySearchFilter.Expression child : children) {
                if (matches(entity, child)) {
                    return true;
                }
            }
            return conditions.isEmpty() && children.isEmpty();
        }

        for (MemorySearchFilter.Condition condition : conditions) {
            if (!matchesCondition(entity, condition)) {
                return false;
            }
        }
        for (MemorySearchFilter.Expression child : children) {
            if (!matches(entity, child)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesCondition(AiGroupMemoryEntity entity, MemorySearchFilter.Condition condition) {
        if (entity == null || condition == null || condition.getField() == null || condition.getOp() == null
                || condition.getValues() == null || condition.getValues().isEmpty()) {
            return true;
        }

        String field = condition.getField();
        String op = condition.getOp().toUpperCase();
        List<String> values = condition.getValues();

        return switch (field) {
            case "memoryType", "memoryTypes" -> matchMemoryType(entity.getMemoryType(), op, values);
            case "memoryKey" -> matchString(entity.getMemoryKey(), op, values);
            case "memoryContent" -> matchString(entity.getMemoryContent(), op, values);
            case "sourceMessage" -> matchString(entity.getSourceMessage(), op, values);
            case "confidenceScore", "minConfidence" -> matchNumber(
                    entity.getConfidenceScore() != null ? entity.getConfidenceScore().doubleValue() : null, op, values);
            case "verifiedOnly" -> matchVerified(entity, values);
            case "recentDays" -> matchRecentDays(entity.getExtractedAt(), op, values);
            default -> true;
        };
    }

    private boolean matchString(String actual, String op, List<String> values) {
        String normalized = actual == null ? "" : actual.toLowerCase();
        return switch (op) {
            case "EQ" -> normalized.equals(values.get(0).toLowerCase());
            case "IN" -> values.stream().anyMatch(value -> normalized.equals(value.toLowerCase()));
            case "CONTAINS" -> values.stream().anyMatch(value -> normalized.contains(value.toLowerCase()));
            default -> true;
        };
    }

    private boolean matchMemoryType(String actual, String op, List<String> values) {
        String normalizedActual = memoryTypeNormalizer.normalize(actual);
        List<String> normalizedValues = values.stream()
                .map(memoryTypeNormalizer::normalize)
                .toList();
        return matchString(normalizedActual, op, normalizedValues);
    }

    private boolean matchNumber(Double actual, String op, List<String> values) {
        if (actual == null) {
            return false;
        }
        double expected = Double.parseDouble(values.get(0));
        return switch (op) {
            case "EQ" -> Double.compare(actual, expected) == 0;
            case "GTE" -> actual >= expected;
            case "LTE" -> actual <= expected;
            default -> true;
        };
    }

    private boolean matchVerified(AiGroupMemoryEntity entity, List<String> values) {
        boolean expected = Boolean.parseBoolean(values.get(0));
        double confidence = entity.getConfidenceScore() != null ? entity.getConfidenceScore().doubleValue() : 0.0;
        boolean actualVerified = confidence >= 0.8;
        return actualVerified == expected;
    }

    private boolean matchRecentDays(LocalDateTime extractedAt, String op, List<String> values) {
        if (extractedAt == null) {
            return false;
        }
        int days = Integer.parseInt(values.get(0));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        return switch (op) {
            case "LTE" -> !extractedAt.isBefore(cutoff);
            case "GTE" -> extractedAt.isBefore(cutoff) || extractedAt.isEqual(cutoff);
            default -> true;
        };
    }
}
