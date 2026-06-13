package com.lx.governance.vectormemory.dto;

import lombok.Data;

import java.util.List;

/**
 * RabbitMQ message for memory search responses
 * Sent from vector-memory-service back to ai-chat-service
 */
@Data
public class MemoryResponseMessage {

    /**
     * Correlation ID matching the request
     */
    private String correlationId;

    /**
     * Whether the request was successful
     */
    private Boolean success;

    /**
     * Error message if success is false
     */
    private String errorMessage;

    /**
     * List of memory items (null if error)
     */
    private List<MemoryItemDTO> data;

    /**
     * Response timestamp (epoch millis)
     */
    private Long timestamp;
}
