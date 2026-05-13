package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Chat response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private Boolean success;
    private String message;
    private String conversationId;
    private String errorMessage;
    private Long timestamp;

    /**
     * Create a success response
     */
    public static ChatResponse success(String message, String conversationId) {
        return ChatResponse.builder()
                .success(true)
                .message(message)
                .conversationId(conversationId)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Create an error response
     */
    public static ChatResponse error(String errorMessage) {
        return ChatResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
