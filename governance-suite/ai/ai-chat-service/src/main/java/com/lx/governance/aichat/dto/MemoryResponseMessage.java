package com.lx.governance.aichat.dto;

import lombok.Data;
import java.util.List;

@Data
public class MemoryResponseMessage {
    /**
     * vector-memory-service 返回给 ai-chat-service 的记忆检索响应消息。
     */
    private String correlationId;
    private Boolean success;
    private String errorMessage;

    /**
     * 召回出的长期记忆列表；失败时通常为空。
     */
    private List<MemoryItemDTO> data;
    private Long timestamp;
}
