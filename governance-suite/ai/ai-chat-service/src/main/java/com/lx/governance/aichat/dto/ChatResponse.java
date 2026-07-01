package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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
    private List<SimilarConversation> recommendations;
    private List<PatientVisibleEvidence> patientVisibleEvidence;

    /**
     * Create a success response
     */
    public static ChatResponse success(String message, String conversationId) {
        return success(message, conversationId, null);
    }

    public static ChatResponse success(
            String message,
            String conversationId,
            List<PatientVisibleEvidence> patientVisibleEvidence) {
        return ChatResponse.builder()
                .success(true)
                .message(message)
                .conversationId(conversationId)
                .patientVisibleEvidence(patientVisibleEvidence)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Create a success response with recommendations
     */
    public static ChatResponse successWithRecommendations(
            String message,
            String conversationId,
            List<SimilarConversation> recommendations) {
        return ChatResponse.builder()
                .success(true)
                .message(message)
                .conversationId(conversationId)
                .recommendations(recommendations)
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

    /**
     * 相似对话推荐
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SimilarConversation {
        private String id;
        private String userMessage;
        private String aiResponse;
        private LocalDateTime createTime;
        private Double score;
    }
}
