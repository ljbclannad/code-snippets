package com.lx.governance.aichat.dto;

import lombok.Data;

@Data
public class UserProfileResponseMessage {
    private String correlationId;
    private Boolean success;
    private String errorMessage;
    private UserProfileDTO data;
    private Long timestamp;
}
