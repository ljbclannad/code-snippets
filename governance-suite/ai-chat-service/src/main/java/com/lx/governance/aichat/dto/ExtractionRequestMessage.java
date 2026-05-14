package com.lx.governance.aichat.dto;

import lombok.Data;

@Data
public class ExtractionRequestMessage {
    private String correlationId;
    private String groupId;
    private String customerId;
    private String userMessage;
    private String aiResponse;
    private Long timestamp;
}
