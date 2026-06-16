package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.dto.MemorySearchFilter;
import com.lx.governance.vectormemory.dto.StructuredMemoryQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class StructuredMemoryQueryCompiler {

    private final MemoryTypeNormalizer memoryTypeNormalizer;

    public MemorySearchFilter compile(StructuredMemoryQuery structuredQuery, MemorySearchFilter fallbackFilter) {
        // 如果上游没有提供 DSL，就完全走兼容路径，沿用原来的固定字段过滤。
        if (structuredQuery == null || structuredQuery.getFilterExpression() == null
                || ((structuredQuery.getFilterExpression().getClauses() == null
                || structuredQuery.getFilterExpression().getClauses().isEmpty())
                && (structuredQuery.getFilterExpression().getExpressions() == null
                || structuredQuery.getFilterExpression().getExpressions().isEmpty()))) {
            return fallbackFilter;
        }

        Set<String> memoryTypes = fallbackFilter != null && fallbackFilter.getMemoryTypes() != null
                ? new LinkedHashSet<>(fallbackFilter.getMemoryTypes())
                : new LinkedHashSet<>();
        Double minConfidence = fallbackFilter != null ? fallbackFilter.getMinConfidence() : null;
        Boolean verifiedOnly = fallbackFilter != null ? fallbackFilter.getVerifiedOnly() : null;
        Integer recentDays = fallbackFilter != null ? fallbackFilter.getRecentDays() : null;

        // 这里做两类事情：
        // 1. 尽量把简单 AND 条件提取成基础 filter，继续复用现有 SQL 下推能力。
        // 2. 保留完整表达式树，交给运行时 matcher 处理 OR / 嵌套 / 文本包含等复杂条件。
        for (StructuredMemoryQuery.FilterClause clause : structuredQuery.getFilterExpression().getClauses() != null
                ? structuredQuery.getFilterExpression().getClauses()
                : List.<StructuredMemoryQuery.FilterClause>of()) {
            if (clause == null || clause.getField() == null || clause.getOp() == null || clause.getValues() == null) {
                continue;
            }
            String field = clause.getField();
            String op = clause.getOp().toUpperCase();
            List<String> values = clause.getValues();

            switch (field) {
                case "memoryType", "memoryTypes" -> {
                    if ("IN".equals(op)) {
                        memoryTypes = values.stream()
                                .map(memoryTypeNormalizer::normalize)
                                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                    }
                }
                case "confidenceScore", "minConfidence" -> {
                    if ("GTE".equals(op) && !values.isEmpty()) {
                        minConfidence = parseDouble(values.get(0), minConfidence);
                    }
                }
                case "verifiedOnly" -> {
                    if ("EQ".equals(op) && !values.isEmpty()) {
                        verifiedOnly = Boolean.parseBoolean(values.get(0));
                    }
                }
                case "recentDays" -> {
                    if ("LTE".equals(op) && !values.isEmpty()) {
                        recentDays = parseInt(values.get(0), recentDays);
                    }
                }
                default -> {
                }
            }
        }

        return MemorySearchFilter.builder()
                .memoryTypes(memoryTypes.isEmpty() ? null : memoryTypes)
                .minConfidence(minConfidence)
                .verifiedOnly(verifiedOnly)
                .recentDays(recentDays)
                .expression(compileExpression(structuredQuery.getFilterExpression()))
                .build();
    }

    private MemorySearchFilter.Expression compileExpression(StructuredMemoryQuery.FilterExpression expression) {
        if (expression == null) {
            return null;
        }

        // 递归把上游 DSL 复制成检索侧表达式树。
        // compiler 不在这里做业务判断，只负责把“描述”翻译成统一执行结构。
        List<MemorySearchFilter.Condition> conditions = new java.util.ArrayList<>();
        for (StructuredMemoryQuery.FilterClause clause : expression.getClauses() != null
                ? expression.getClauses()
                : List.<StructuredMemoryQuery.FilterClause>of()) {
            conditions.add(MemorySearchFilter.Condition.builder()
                    .field(clause.getField())
                    .op(clause.getOp())
                    .values(clause.getValues())
                    .build());
        }

        List<MemorySearchFilter.Expression> children = new java.util.ArrayList<>();
        for (StructuredMemoryQuery.FilterExpression child : expression.getExpressions() != null
                ? expression.getExpressions()
                : List.<StructuredMemoryQuery.FilterExpression>of()) {
            MemorySearchFilter.Expression compiledChild = compileExpression(child);
            if (compiledChild != null) {
                children.add(compiledChild);
            }
        }

        return MemorySearchFilter.Expression.builder()
                .operator(expression.getOperator())
                .conditions(conditions)
                .expressions(children)
                .build();
    }

    private Double parseDouble(String raw, Double defaultValue) {
        try {
            return Double.parseDouble(raw);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private Integer parseInt(String raw, Integer defaultValue) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
