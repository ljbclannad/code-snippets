package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.dto.MemorySearchFilter;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredMemoryFilterMatcherTest {

    private final StructuredMemoryFilterMatcher matcher = new StructuredMemoryFilterMatcher();

    @Test
    void shouldMatchNestedOrExpression() {
        AiGroupMemoryEntity entity = new AiGroupMemoryEntity();
        entity.setMemoryType("DIET");
        entity.setMemoryKey("晚饭建议");
        entity.setMemoryContent("控糖期间晚饭少油少盐");
        entity.setConfidenceScore(BigDecimal.valueOf(0.85));
        entity.setExtractedAt(LocalDateTime.now().minusDays(7));

        MemorySearchFilter.Expression expression = MemorySearchFilter.Expression.builder()
                .operator("AND")
                .conditions(List.of(
                        MemorySearchFilter.Condition.builder()
                                .field("memoryType")
                                .op("IN")
                                .values(List.of("DIET"))
                                .build()))
                .expressions(List.of(
                        MemorySearchFilter.Expression.builder()
                                .operator("OR")
                                .conditions(List.of(
                                        MemorySearchFilter.Condition.builder()
                                                .field("memoryKey")
                                                .op("CONTAINS")
                                                .values(List.of("晚饭"))
                                                .build(),
                                        MemorySearchFilter.Condition.builder()
                                                .field("memoryContent")
                                                .op("CONTAINS")
                                                .values(List.of("控糖"))
                                                .build()))
                                .build()))
                .build();

        assertTrue(matcher.matches(entity, expression));
    }

    @Test
    void shouldRejectEntityWhenNestedExpressionFails() {
        AiGroupMemoryEntity entity = new AiGroupMemoryEntity();
        entity.setMemoryType("SYMPTOM");
        entity.setMemoryKey("头晕");
        entity.setMemoryContent("最近头晕");
        entity.setConfidenceScore(BigDecimal.valueOf(0.65));
        entity.setExtractedAt(LocalDateTime.now().minusDays(120));

        MemorySearchFilter.Expression expression = MemorySearchFilter.Expression.builder()
                .operator("AND")
                .conditions(List.of(
                        MemorySearchFilter.Condition.builder()
                                .field("memoryType")
                                .op("IN")
                                .values(List.of("DIET"))
                                .build(),
                        MemorySearchFilter.Condition.builder()
                                .field("confidenceScore")
                                .op("GTE")
                                .values(List.of("0.8"))
                                .build()))
                .build();

        assertFalse(matcher.matches(entity, expression));
    }
}
