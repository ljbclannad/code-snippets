package com.lx.governance.aichat.dto;

import lombok.Data;

@Data
public class UserProfileRequestMessage {
    private String correlationId;
    private String customerId;
    private Long timestamp;
}
