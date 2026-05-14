package com.lx.governance.aichat.dto;

import lombok.Data;

@Data
public class MemoryRequestMessage {
    private String correlationId;
    private String groupId;
    private String customerId;
    private String query;
    private Integer topK;
    private Long timestamp;
}
