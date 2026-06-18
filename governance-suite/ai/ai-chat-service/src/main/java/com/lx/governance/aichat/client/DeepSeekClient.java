package com.lx.governance.aichat.client;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.context.ContextBudgetManager;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatGenerationResult;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.exception.AiServiceException;
import com.lx.governance.aichat.monitor.TokenMonitor;
import com.lx.governance.aichat.monitor.TokenUsageMetrics;
import com.lx.governance.agentplatform.model.PlatformCostScope;
import com.lx.governance.agentplatform.runtime.PlatformMetricsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * DeepSeek API client for AI chat integration
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekClient {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ContextBudgetManager contextBudgetManager;
    private final TokenMonitor tokenMonitor;
    private final ContextBudgetProperties properties;

    private PlatformMetricsService platformMetricsService;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String model;

    @Value("${ai-chat.metrics.cost.prompt-price-per-1k-tokens:0}")
    private double promptPricePer1kTokens;

    @Value("${ai-chat.metrics.cost.completion-price-per-1k-tokens:0}")
    private double completionPricePer1kTokens;

    @Autowired(required = false)
    void setPlatformMetricsService(PlatformMetricsService platformMetricsService) {
        this.platformMetricsService = platformMetricsService;
    }

    /**
     * Send chat request to DeepSeek API
     *
     * @param request        chat request
     * @param userProfile    user profile (nullable)
     * @param sessionContext session context (nullable)
     * @param memories       memory items (nullable)
     * @return unified chat generation result
     */
    public ChatGenerationResult chat(ChatRequest request, UserProfileDTO userProfile, SessionContext sessionContext,
            List<MemoryItemDTO> memories) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("Calling DeepSeek API for customer: {}, conversationId: {}",
                    request.getCustomerId(), request.getConversationId());

            // Build system prompt with context
            String systemPrompt = buildSystemPrompt(request, userProfile, sessionContext, memories);
            String userMessage = request.getMessage();

            // Construct Prompt with Spring AI (using SystemMessage and UserMessage)
            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userMessage)),
                    OpenAiChatOptions.builder()
                            .withModel(model)
                            .withTemperature(0.7)
                            .withMaxTokens(2000)
                            .build());

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
            recordPlatformTokenCost(usage);

            // Convert to the internal unified result format
            return buildChatGenerationResult(content, usage, request.getConversationId());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("DeepSeek API call failed after {}ms for customer: {}",
                    duration, request.getCustomerId(), e);
            if (isTimeoutException(e)) {
                throw AiServiceException.timeout("DeepSeek API 调用超时");
            }
            throw AiServiceException.apiError("DeepSeek API 调用失败", e);
        }
    }

    public ChatGenerationResult chat(ChatRequest request, UserProfileDTO userProfile, SessionContext sessionContext,
            LayeredMemories layeredMemories) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("Calling DeepSeek API for customer: {}, conversationId: {}",
                    request.getCustomerId(), request.getConversationId());

            String systemPrompt = contextBudgetManager.buildBudgetedSystemPrompt(
                    userProfile,
                    sessionContext,
                    layeredMemories,
                    request.getCustomerId(),
                    request.getConversationId(),
                    request.getMessage());
            String userMessage = request.getMessage();

            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userMessage)),
                    OpenAiChatOptions.builder()
                            .withModel(model)
                            .withTemperature(0.7)
                            .withMaxTokens(2000)
                            .build());

            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getContent();
            var usage = response.getMetadata().getUsage();
            if (usage == null) {
                throw new RuntimeException("Missing token usage metadata from API response");
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("DeepSeek API call completed in {}ms, tokens: prompt={}, completion={}, total={}",
                    duration,
                    usage.getPromptTokens(),
                    usage.getGenerationTokens(),
                    usage.getTotalTokens());

            recordTokenUsage(usage, duration);
            recordPlatformTokenCost(usage);
            return buildChatGenerationResult(content, usage, request.getConversationId());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("DeepSeek API call failed after {}ms for customer: {}",
                    duration, request.getCustomerId(), e);
            if (isTimeoutException(e)) {
                throw AiServiceException.timeout("DeepSeek API 调用超时");
            }
            throw AiServiceException.apiError("DeepSeek API 调用失败", e);
        }
    }

    /**
     * 流式调用 DeepSeek API
     *
     * @param request        chat request
     * @param userProfile    user profile (nullable)
     * @param sessionContext session context (nullable)
     * @param memories       memory items (nullable)
     * @return Flux of StreamEvent
     */
    public Flux<StreamEvent> chatStream(ChatRequest request, UserProfileDTO userProfile,
            SessionContext sessionContext, List<MemoryItemDTO> memories) {
        long startTime = System.currentTimeMillis();

        return Flux.defer(() -> {
            try {
                log.info("Calling DeepSeek API (streaming) for customer: {}, conversationId: {}",
                        request.getCustomerId(), request.getConversationId());

                // Build system prompt
                String systemPrompt = buildSystemPrompt(request, userProfile, sessionContext, memories);
                String userMessage = request.getMessage();

                // Construct Prompt
                Prompt prompt = new Prompt(
                        List.of(
                                new SystemMessage(systemPrompt),
                                new UserMessage(userMessage)),
                        OpenAiChatOptions.builder()
                                .withModel(model)
                                .withTemperature(0.7)
                                .withMaxTokens(2000)
                                .build());

                // 发送开始事件
                String conversationId = request.getConversationId();

                // Call StreamingChatModel
                return Flux.concat(
                        Flux.just(StreamEvent.start(conversationId)),
                        streamingChatModel.stream(prompt)
                                .map(response -> {
                                    String content = response.getResult().getOutput().getContent();
                                    return StreamEvent.content(content);
                                })
                                .doOnComplete(() -> {
                                    long duration = System.currentTimeMillis() - startTime;
                                    log.info("DeepSeek API streaming completed in {}ms", duration);
                                }),
                        Flux.just(StreamEvent.done(conversationId, null))
                );

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log.error("DeepSeek API streaming failed after {}ms for customer: {}",
                        duration, request.getCustomerId(), e);
                return Flux.just(StreamEvent.error(
                        "AI 服务调用失败: " + e.getMessage(),
                        3001
                ));
            }
        }).onErrorResume(e -> {
            log.error("DeepSeek API streaming error", e);
            return Flux.just(StreamEvent.error(
                    "AI 服务调用异常: " + e.getMessage(),
                    3001
            ));
        });
    }

    public Flux<StreamEvent> chatStream(ChatRequest request, UserProfileDTO userProfile,
            SessionContext sessionContext, LayeredMemories layeredMemories) {
        long startTime = System.currentTimeMillis();

        return Flux.defer(() -> {
            try {
                log.info("Calling DeepSeek API (streaming) for customer: {}, conversationId: {}",
                        request.getCustomerId(), request.getConversationId());

                String systemPrompt = contextBudgetManager.buildBudgetedSystemPrompt(
                        userProfile,
                        sessionContext,
                        layeredMemories,
                        request.getCustomerId(),
                        request.getConversationId(),
                        request.getMessage());
                String userMessage = request.getMessage();

                Prompt prompt = new Prompt(
                        List.of(
                                new SystemMessage(systemPrompt),
                                new UserMessage(userMessage)),
                        OpenAiChatOptions.builder()
                                .withModel(model)
                                .withTemperature(0.7)
                                .withMaxTokens(2000)
                                .build());

                String conversationId = request.getConversationId();

                return Flux.concat(
                        Flux.just(StreamEvent.start(conversationId)),
                        streamingChatModel.stream(prompt)
                                .map(response -> {
                                    String content = response.getResult().getOutput().getContent();
                                    return StreamEvent.content(content);
                                })
                                .doOnComplete(() -> {
                                    long duration = System.currentTimeMillis() - startTime;
                                    log.info("DeepSeek API streaming completed in {}ms", duration);
                                }),
                        Flux.just(StreamEvent.done(conversationId, null))
                );

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log.error("DeepSeek API streaming failed after {}ms for customer: {}",
                        duration, request.getCustomerId(), e);
                return Flux.just(StreamEvent.error(
                        "AI 服务调用失败: " + e.getMessage(),
                        3001
                ));
            }
        }).onErrorResume(e -> {
            log.error("DeepSeek API streaming error", e);
            return Flux.just(StreamEvent.error(
                    "AI 服务调用异常: " + e.getMessage(),
                    3001
            ));
        });
    }

    /**
     * Build system prompt with user profile and memories
     */
    private String buildSystemPrompt(ChatRequest request, UserProfileDTO userProfile, SessionContext sessionContext,
            List<MemoryItemDTO> memories) {
        // 使用上下文预算管理器构建系统提示词（包含会话上下文和对话历史）
        return contextBudgetManager.buildBudgetedSystemPrompt(
                userProfile,
                sessionContext,
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
                            usage.getTotalTokens().intValue() > totalThreshold);
        }

        // 记录到监控系统
        tokenMonitor.recordTokenUsage(metrics);
    }

    private void recordPlatformTokenCost(org.springframework.ai.chat.metadata.Usage usage) {
        if (usage == null || platformMetricsService == null) {
            return;
        }
        int promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : 0;
        int completionTokens = usage.getGenerationTokens() != null ? usage.getGenerationTokens().intValue() : 0;
        int totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : 0;
        double estimatedCost = estimateCost(promptTokens, completionTokens);
        platformMetricsService.recordTokenCost(
                PlatformCostScope.DIRECT_CHAT,
                "ai-chat-direct",
                promptTokens,
                completionTokens,
                totalTokens,
                estimatedCost);
    }

    private double estimateCost(int promptTokens, int completionTokens) {
        return (promptTokens / 1000.0D) * Math.max(0D, promptPricePer1kTokens)
                + (completionTokens / 1000.0D) * Math.max(0D, completionPricePer1kTokens);
    }

    private boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Convert Spring AI response to the internal chat result format.
     */
    private ChatGenerationResult buildChatGenerationResult(String content, org.springframework.ai.chat.metadata.Usage usage,
            String conversationId) {
        ChatGenerationResult chatResult = new ChatGenerationResult();

        chatResult.setAnswer(content);

        // Use existing conversationId or generate new one
        chatResult
                .setConversationId(conversationId != null ? conversationId : "conv-" + UUID.randomUUID().toString());
        chatResult.setMessageId("msg-" + UUID.randomUUID().toString());

        // Set metadata
        ChatGenerationResult.Metadata metadata = new ChatGenerationResult.Metadata();
        if (usage != null) {
            metadata.setUsage(String.format("prompt_tokens=%d, completion_tokens=%d, total_tokens=%d",
                    usage.getPromptTokens().intValue(),
                    usage.getGenerationTokens().intValue(),
                    usage.getTotalTokens().intValue()));
        }
        chatResult.setMetadata(metadata);

        return chatResult;
    }

    // ==================== No longer needed - using Spring AI ====================
}
