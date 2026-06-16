package com.lx.governance.vectormemory.dto;

import lombok.Data;

import java.util.List;

/**
 * vector-memory-service 返回给 ai-chat-service 的记忆检索响应消息。
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
     * 检索命中的记忆列表；失败时通常为空。
     */
    private List<MemoryItemDTO> data;

    /**
     * Response timestamp (epoch millis)
     */
    private Long timestamp;
}
