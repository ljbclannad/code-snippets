package com.lx.governance.vectormemory.dto;

import java.util.Set;

import lombok.Data;

/**
 * ai-chat-service 发送给 vector-memory-service 的记忆检索请求消息。
 *
 * <p>这份消息既承载基础查询参数，也承载上游规划出的结构化检索语义，
 * 供记忆服务完成召回、过滤、重排和治理。</p>
 */
@Data
public class MemoryRequestMessage {

    /**
     * Correlation ID for request-response matching
     */
    private String correlationId;

    /**
     * 租户/分组 ID，用于限定检索边界。
     */
    private String groupId;

    /**
     * Customer ID
     */
    private String customerId;

    /**
     * 面向向量召回的查询文本。
     */
    private String query;

    /**
     * 期望返回的 Top K 数量。
     */
    private Integer topK;

    /**
     * Request timestamp (epoch millis)
     */
    private Long timestamp;

    /**
     * 兼容简单场景的扁平记忆类型过滤。
     */
    private Set<String> memoryTypes;
    /**
     * 召回结果最小置信度阈值。
     */
    private Double minConfidence;
    /**
     * 是否仅返回已确认记忆。
     */
    private Boolean verifiedOnly;
    /**
     * 是否限定最近 N 天的记忆。
     */
    private Integer recentDays;
    /**
     * 更完整的结构化检索计划，优先表达复杂过滤语义。
     */
    private StructuredMemoryQuery structuredQuery;
}
