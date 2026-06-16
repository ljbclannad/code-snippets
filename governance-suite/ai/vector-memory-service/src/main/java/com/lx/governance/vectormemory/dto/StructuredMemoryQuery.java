package com.lx.governance.vectormemory.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Data
public class StructuredMemoryQuery {
    /**
     * 上游 ai-chat-service 规划出的结构化记忆检索语义。
     */
    private String originalMessage;
    /**
     * 用于向量召回的语义查询。
     */
    private String semanticQuery;
    /**
     * 辅助检索和重排的关键词。
     */
    private List<String> keywords;
    /**
     * 扁平过滤条件。
     */
    private MemoryFilter filter;
    /**
     * 树形过滤表达式。
     */
    private FilterExpression filterExpression;
    /**
     * 计划生成来源。
     */
    private String planSource;
    /**
     * 计划生成原因或解释。
     */
    private String planReason;
    /**
     * 当前查询是否依赖会话上下文。
     */
    private Boolean contextDependent;

    @Data
    public static class MemoryFilter {
        /**
         * 允许参与检索的记忆类型。
         */
        private Set<String> memoryTypes;
        /**
         * 结果最小置信度。
         */
        private Double minConfidence;
        /**
         * 是否只返回已确认记忆。
         */
        private Boolean verifiedOnly;
        /**
         * 最近时间窗限制，单位天。
         */
        private Integer recentDays;
    }

    @Data
    public static class FilterExpression {
        /**
         * 表达式节点操作符，例如 AND / OR。
         */
        private String operator;
        /**
         * 当前节点的原子条件。
         */
        private List<FilterClause> clauses = new ArrayList<>();
        /**
         * 子表达式列表。
         */
        private List<FilterExpression> expressions = new ArrayList<>();
    }

    @Data
    public static class FilterClause {
        /**
         * 目标字段名。
         */
        private String field;
        /**
         * 比较操作符。
         */
        private String op;
        /**
         * 比较值列表。
         */
        private List<String> values = new ArrayList<>();
    }
}
