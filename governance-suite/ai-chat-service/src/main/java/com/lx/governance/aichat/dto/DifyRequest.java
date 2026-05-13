package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Dify API request DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DifyRequest {

    private String query;
    private String user;
    private String conversationId;
    private Map<String, Object> inputs;
    private Boolean responseMode;
}
