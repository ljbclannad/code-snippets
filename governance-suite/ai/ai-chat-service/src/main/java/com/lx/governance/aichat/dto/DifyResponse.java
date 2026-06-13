package com.lx.governance.aichat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Dify API response DTO
 */
@Data
public class DifyResponse {

    private String answer;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("message_id")
    private String messageId;

    private Metadata metadata;

    /**
     * Metadata inner class
     */
    @Data
    public static class Metadata {
        private String usage;
        private Integer retrieverResources;
    }
}
