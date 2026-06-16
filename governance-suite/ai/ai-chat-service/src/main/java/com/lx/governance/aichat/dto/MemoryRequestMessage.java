package com.lx.governance.aichat.dto;

import java.util.Set;

import lombok.Data;

@Data
public class MemoryRequestMessage {
    /**
     * ai-chat-service 发送给 vector-memory-service 的记忆检索请求消息。
     *
     * <p>这是一份 RabbitMQ RPC 风格载荷，用于把当前租户、用户、查询语句和
     * 检索治理条件一起传给记忆服务，并用 correlationId 关联异步响应。</p>
     */
    private String correlationId;
    private String groupId;
    private String customerId;
    private String query;
    private Integer topK;
    private Long timestamp;

    /**
     * 兼容基础检索场景的扁平过滤条件。
     */
    private Set<String> memoryTypes;
    private Double minConfidence;
    private Boolean verifiedOnly;
    private Integer recentDays;

    /**
     * 更完整的结构化检索计划，优先表达复杂的检索语义。
     */
    private StructuredMemoryQuery structuredQuery;
}
