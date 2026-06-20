package com.lx.governance.aichat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Chat request DTO
 */
@Data
public class ChatRequest {

    @NotBlank(message = "Group ID cannot be blank")
    private String groupId;

    private String customerId;

    private String userId;

    private String tenantId;

    @NotBlank(message = "Message cannot be blank")
    private String message;

    private String conversationId;
}
