package com.lx.governance.vectormemory.dto;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemorySearchFilter {

    private Set<String> memoryTypes;
    private Double minConfidence;
    private Boolean verifiedOnly;
    private Integer recentDays;
    private Expression expression;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Expression {
        private String operator;
        @Builder.Default
        private List<Condition> conditions = new ArrayList<>();
        @Builder.Default
        private List<Expression> expressions = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Condition {
        private String field;
        private String op;
        @Builder.Default
        private List<String> values = new ArrayList<>();
    }
}
