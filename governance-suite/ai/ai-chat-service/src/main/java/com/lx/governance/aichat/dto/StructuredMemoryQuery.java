package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class StructuredMemoryQuery {

    private String originalMessage;
    private String semanticQuery;
    @Builder.Default
    private List<String> keywords = new ArrayList<>();
    private MemoryFilter filter;
    private FilterExpression filterExpression;
    private String planSource;
    private String planReason;
    private Boolean contextDependent;

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryFilter {
        @Builder.Default
        private Set<String> memoryTypes = new LinkedHashSet<>();
        private Double minConfidence;
        private Boolean verifiedOnly;
        private Integer recentDays;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterExpression {
        private String operator;
        @Builder.Default
        private List<FilterClause> clauses = new ArrayList<>();
        @Builder.Default
        private List<FilterExpression> expressions = new ArrayList<>();
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterClause {
        private String field;
        private String op;
        @Builder.Default
        private List<String> values = new ArrayList<>();
    }
}
