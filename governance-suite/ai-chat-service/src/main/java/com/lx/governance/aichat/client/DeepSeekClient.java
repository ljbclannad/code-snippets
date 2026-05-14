package com.lx.governance.aichat.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.context.ContextBudgetManager;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.monitor.TokenMonitor;
import com.lx.governance.aichat.monitor.TokenUsageMetrics;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * DeepSeek API client for AI chat integration
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekClient {

    private final RestClient deepSeekRestClient;
    private final ContextBudgetManager contextBudgetManager;
    private final TokenMonitor tokenMonitor;
    private final ContextBudgetProperties properties;

    @Value("${deepseek.model:deepseek-chat}")
    private String model;

    /**
     * Send chat request to DeepSeek API
     *
     * @param request     chat request
     * @param userProfile user profile (nullable)
     * @param memories    memory items (nullable)
     * @return DifyResponse (compatible format)
     */
    public DifyResponse chat(ChatRequest request, UserProfileDTO userProfile, List<MemoryItemDTO> memories) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("Calling DeepSeek API for customer: {}, conversationId: {}",
                    request.getCustomerId(), request.getConversationId());

            DeepSeekRequest deepSeekRequest = buildDeepSeekRequest(request, userProfile, memories);

            DeepSeekResponse response = deepSeekRestClient.post()
                    .uri("/chat/completions")
                    .body(deepSeekRequest)
                    .retrieve()
                    .body(DeepSeekResponse.class);

            long duration = System.currentTimeMillis() - startTime;
            log.info("DeepSeek API call completed in {}ms, tokens: prompt={}, completion={}, total={}",
                    duration,
                    response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens(),
                    response.getUsage().getTotalTokens());

            // 记录 Token 使用情况
            recordTokenUsage(response, duration);

            // Convert to DifyResponse format
            return convertToDifyResponse(response, request.getConversationId());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("DeepSeek API call failed after {}ms for customer: {}",
                    duration, request.getCustomerId(), e);
            throw new RuntimeException("Failed to call DeepSeek API: " + e.getMessage(), e);
        }
    }

    /**
     * Build DeepSeek API request with user profile and memories
     */
    private DeepSeekRequest buildDeepSeekRequest(ChatRequest request, UserProfileDTO userProfile, List<MemoryItemDTO> memories) {
        List<Message> messages = new ArrayList<>();

        // System message with context
        String systemPrompt = buildSystemPrompt(userProfile, memories, request.getMessage());
        messages.add(Message.builder()
                .role("system")
                .content(systemPrompt)
                .build());

        // User message
        messages.add(Message.builder()
                .role("user")
                .content(request.getMessage())
                .build());

        return DeepSeekRequest.builder()
                .model(model)
                .messages(messages)
                .temperature(0.7)
                .maxTokens(2000)
                .build();
    }

    /**
     * Build system prompt with user profile and memories
     */
    private String buildSystemPrompt(UserProfileDTO userProfile, List<MemoryItemDTO> memories, String userMessage) {
        // 使用上下文预算管理器构建系统提示词
        return contextBudgetManager.buildBudgetedSystemPrompt(userProfile, memories, userMessage);
    }

    /**
     * 记录 Token 使用情况
     */
    private void recordTokenUsage(DeepSeekResponse response, long duration) {
        if (response.getUsage() == null) {
            return;
        }

        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .timestamp(java.time.LocalDateTime.now())
                // 从 ContextBudgetManager 获取各部分的 token 使用情况
                .coreProfileTokens(contextBudgetManager.getLastCoreProfileTokens())
                .memoriesTokens(contextBudgetManager.getLastMemoriesTokens())
                .conversationTokens(contextBudgetManager.getLastConversationTokens())
                .extendedProfileTokens(contextBudgetManager.getLastExtendedProfileTokens())
                .systemPromptTokens(contextBudgetManager.getLastTotalTokens())
                // API 返回的 token 使用情况
                .promptTokens(response.getUsage().getPromptTokens())
                .completionTokens(response.getUsage().getCompletionTokens())
                .totalTokens(response.getUsage().getTotalTokens())
                // 性能指标
                .contextBuildTime(duration)
                .apiResponseTime(duration)
                .build();

        // 检查是否超预算
        int totalBudget = properties.getTotalBudget();
        metrics.setOverBudget(response.getUsage().getPromptTokens() > totalBudget);

        // 检查是否需要告警
        if (properties.getAlert().isEnabled()) {
            int promptThreshold = properties.getAlert().getPromptTokensThreshold();
            int totalThreshold = properties.getAlert().getTotalTokensThreshold();
            metrics.setShouldAlert(
                    response.getUsage().getPromptTokens() > promptThreshold ||
                    response.getUsage().getTotalTokens() > totalThreshold
            );
        }

        // 记录到监控系统
        tokenMonitor.recordTokenUsage(metrics);
    }

    /**
     * Convert DeepSeek response to Dify response format
     */
    private DifyResponse convertToDifyResponse(DeepSeekResponse deepSeekResponse, String conversationId) {
        DifyResponse difyResponse = new DifyResponse();

        if (deepSeekResponse.getChoices() != null && !deepSeekResponse.getChoices().isEmpty()) {
            String answer = deepSeekResponse.getChoices().get(0).getMessage().getContent();
            difyResponse.setAnswer(answer);
        }

        // Use existing conversationId or generate new one
        difyResponse.setConversationId(conversationId != null ? conversationId : "conv-" + UUID.randomUUID().toString());
        difyResponse.setMessageId("msg-" + UUID.randomUUID().toString());

        // Set metadata
        DifyResponse.Metadata metadata = new DifyResponse.Metadata();
        if (deepSeekResponse.getUsage() != null) {
            metadata.setUsage(String.format("prompt_tokens=%d, completion_tokens=%d, total_tokens=%d",
                    deepSeekResponse.getUsage().getPromptTokens(),
                    deepSeekResponse.getUsage().getCompletionTokens(),
                    deepSeekResponse.getUsage().getTotalTokens()));
        }
        difyResponse.setMetadata(metadata);

        return difyResponse;
    }

    // ==================== DeepSeek API DTOs ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class DeepSeekRequest {
        private String model;
        private List<Message> messages;
        private Double temperature;
        @JsonProperty("max_tokens")
        private Integer maxTokens;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class Message {
        private String role;
        private String content;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class DeepSeekResponse {
        private String id;
        private String object;
        private Long created;
        private String model;
        private List<Choice> choices;
        private Usage usage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class Choice {
        private Integer index;
        private Message message;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class Usage {
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;
        @JsonProperty("completion_tokens")
        private Integer completionTokens;
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
