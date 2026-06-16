package com.lx.governance.vectormemory.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Data
public class StructuredMemoryQuery {
    private String originalMessage;
    private String semanticQuery;
    private List<String> keywords;
    private MemoryFilter filter;
    private FilterExpression filterExpression;
    private String planSource;
    private String planReason;
    private Boolean contextDependent;

    @Data
    public static class MemoryFilter {
        private Set<String> memoryTypes;
        private Double minConfidence;
        private Boolean verifiedOnly;
        private Integer recentDays;
    }

    @Data
    public static class FilterExpression {
        private String operator;
        private List<FilterClause> clauses = new ArrayList<>();
        private List<FilterExpression> expressions = new ArrayList<>();
    }

    @Data
    public static class FilterClause {
        private String field;
        private String op;
        private List<String> values = new ArrayList<>();
    }
}
