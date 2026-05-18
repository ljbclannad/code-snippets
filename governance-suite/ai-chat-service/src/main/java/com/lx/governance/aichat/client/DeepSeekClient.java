package com.lx.governance.aichat.client;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.context.ContextBudgetManager;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.monitor.TokenMonitor;
import com.lx.governance.aichat.monitor.TokenUsageMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * DeepSeek API client for AI chat integration
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekClient {

    private final ChatModel chatModel;
    private final ContextBudgetManager contextBudgetManager;
    private final TokenMonitor tokenMonitor;
    private final ContextBudgetProperties properties;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
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

            // Build system prompt with context
            String systemPrompt = buildSystemPrompt(request, userProfile, memories);
            String userMessage = request.getMessage();

            // Construct Prompt with Spring AI (using SystemMessage and UserMessage)
            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userMessage)
                    ),
                    OpenAiChatOptions.builder()
                            .withModel(model)
                            .withTemperature(0.7)
                            .withMaxTokens(2000)
                            .build()
            );

            // Call ChatModel
            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getContent();

            // Extract token usage from ChatResponse
            var usage = response.getMetadata().getUsage();
            if (usage == null) {
                log.error("No usage metadata returned from DeepSeek API");
                throw new RuntimeException("Missing token usage metadata from API response");
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("DeepSeek API call completed in {}ms, tokens: prompt={}, completion={}, total={}",
                    duration,
                    usage.getPromptTokens(),
                    usage.getGenerationTokens(),
                    usage.getTotalTokens());

            // Record token usage with monitoring logic
            recordTokenUsage(usage, duration);

            // Convert to DifyResponse format
            return convertToDifyResponse(content, usage, request.getConversationId());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("DeepSeek API call failed after {}ms for customer: {}",
                    duration, request.getCustomerId(), e);
            throw new RuntimeException("Failed to call DeepSeek API: " + e.getMessage(), e);
        }
    }


    /**
     * Build system prompt with user profile and memories
     */
    private String buildSystemPrompt(ChatRequest request, UserProfileDTO userProfile, List<MemoryItemDTO> memories) {
        // 使用上下文预算管理器构建系统提示词（包含对话历史）
        // TODO: 传入SessionContext参数，当前暂时传null
        return contextBudgetManager.buildBudgetedSystemPrompt(
                userProfile,
                null, // SessionContext - 待Task 7集成
                memories,
                request.getCustomerId(),
                request.getConversationId(),
                request.getMessage());
    }

    /**
     * 记录 Token 使用情况
     */
    private void recordTokenUsage(org.springframework.ai.chat.metadata.Usage usage, long duration) {
        if (usage == null) {
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
                .promptTokens(usage.getPromptTokens().intValue())
                .completionTokens(usage.getGenerationTokens().intValue())
                .totalTokens(usage.getTotalTokens().intValue())
                // 性能指标
                .contextBuildTime(duration)
                .apiResponseTime(duration)
                .build();

        // 检查是否超预算
        int totalBudget = properties.getTotalBudget();
        metrics.setOverBudget(usage.getPromptTokens().intValue() > totalBudget);

        // 检查是否需要告警
        if (properties.getAlert().isEnabled()) {
            int promptThreshold = properties.getAlert().getPromptTokensThreshold();
            int totalThreshold = properties.getAlert().getTotalTokensThreshold();
            metrics.setShouldAlert(
                    usage.getPromptTokens().intValue() > promptThreshold ||
                    usage.getTotalTokens().intValue() > totalThreshold
            );
        }

        // 记录到监控系统
        tokenMonitor.recordTokenUsage(metrics);
    }

    /**
     * Convert Spring AI response to Dify response format
     */
    private DifyResponse convertToDifyResponse(String content, org.springframework.ai.chat.metadata.Usage usage, String conversationId) {
        DifyResponse difyResponse = new DifyResponse();

        difyResponse.setAnswer(content);

        // Use existing conversationId or generate new one
        difyResponse.setConversationId(conversationId != null ? conversationId : "conv-" + UUID.randomUUID().toString());
        difyResponse.setMessageId("msg-" + UUID.randomUUID().toString());

        // Set metadata
        DifyResponse.Metadata metadata = new DifyResponse.Metadata();
        if (usage != null) {
            metadata.setUsage(String.format("prompt_tokens=%d, completion_tokens=%d, total_tokens=%d",
                    usage.getPromptTokens().intValue(),
                    usage.getGenerationTokens().intValue(),
                    usage.getTotalTokens().intValue()));
        }
        difyResponse.setMetadata(metadata);

        return difyResponse;
    }

    // ==================== No longer needed - using Spring AI ====================
}
