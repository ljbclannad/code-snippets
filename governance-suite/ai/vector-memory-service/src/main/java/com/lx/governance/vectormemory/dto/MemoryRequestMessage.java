package com.lx.governance.vectormemory.dto;

import java.util.Set;

import lombok.Data;

/**
 * RabbitMQ message for memory search requests
 * Sent from ai-chat-service to vector-memory-service
 */
@Data
public class MemoryRequestMessage {

    /**
     * Correlation ID for request-response matching
     */
    private String correlationId;

    /**
     * Group ID (tenant/organization)
     */
    private String groupId;

    /**
     * Customer ID
     */
    private String customerId;

    /**
     * Query text for memory search
     */
    private String query;

    /**
     * Number of top results to return
     */
    private Integer topK;

    /**
     * Request timestamp (epoch millis)
     */
    private Long timestamp;

    private Set<String> memoryTypes;
    private Double minConfidence;
    private Boolean verifiedOnly;
    private Integer recentDays;
    private StructuredMemoryQuery structuredQuery;
}
