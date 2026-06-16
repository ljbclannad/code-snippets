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

    /**
     * ai-chat-service 为 vector-memory-service 生成的结构化检索计划。
     *
     * <p>它把“用户原始消息”转换成“可检索的语义查询 + 显式过滤条件”，
     * 让下游既能做向量召回，也能结合记忆类型、时间窗、置信度等约束做治理。</p>
     */
    private String originalMessage;
    /**
     * 面向向量召回的主查询语句，通常比原始消息更适合检索。
     */
    private String semanticQuery;

    /**
     * 辅助检索和重排的关键词列表。
     */
    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    /**
     * 扁平过滤器，适合表达常见治理条件。
     */
    private MemoryFilter filter;

    /**
     * 支持 AND/OR 组合的树形过滤表达式，适合更复杂的检索约束。
     */
    private FilterExpression filterExpression;

    /**
     * 这份检索计划的来源，例如规则规划或 LLM 重写。
     */
    private String planSource;

    /**
     * 记录为什么会生成当前检索计划，便于排查召回效果。
     */
    private String planReason;

    /**
     * 标记当前查询是否强依赖本轮会话上下文。
     */
    private Boolean contextDependent;

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryFilter {
        /**
         * 允许检索的记忆类型集合，例如长期事实、偏好、事件等。
         */
        @Builder.Default
        private Set<String> memoryTypes = new LinkedHashSet<>();

        /**
         * 召回结果需要满足的最小置信度阈值。
         */
        private Double minConfidence;

        /**
         * 是否只返回已人工/规则确认过的记忆。
         */
        private Boolean verifiedOnly;

        /**
         * 限定只检索最近 N 天内的记忆。
         */
        private Integer recentDays;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterExpression {
        /**
         * 当前表达式节点的布尔操作符，例如 AND / OR。
         */
        private String operator;

        /**
         * 当前节点直接包含的原子过滤条件。
         */
        @Builder.Default
        private List<FilterClause> clauses = new ArrayList<>();

        /**
         * 当前节点下的子表达式，支持递归组合复杂过滤逻辑。
         */
        @Builder.Default
        private List<FilterExpression> expressions = new ArrayList<>();
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterClause {
        /**
         * 要过滤的业务字段名。
         */
        private String field;

        /**
         * 条件操作符，例如 eq、in、gte。
         */
        private String op;

        /**
         * 当前条件对应的比较值列表。
         */
        @Builder.Default
        private List<String> values = new ArrayList<>();
    }
}
