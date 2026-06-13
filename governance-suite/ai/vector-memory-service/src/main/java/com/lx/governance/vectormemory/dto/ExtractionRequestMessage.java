package com.lx.governance.vectormemory.dto;

import lombok.Data;

/**
 * RabbitMQ message for memory extraction requests
 * Sent from ai-chat-service to vector-memory-service after conversations
 */
@Data
public class ExtractionRequestMessage {

    /**
     * Correlation ID for tracking
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
     * User's message in the conversation
     */
    private String userMessage;

    /**
     * AI's response in the conversation
     */
    private String aiResponse;

    /**
     * Request timestamp (epoch millis)
     */
    private Long timestamp;
}
