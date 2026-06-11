package com.lx.governance.aichat.service;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.dto.*;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import com.lx.governance.aichat.quota.QuotaService;
import com.lx.governance.aichat.stream.StreamResponseAccumulator;
import com.lx.governance.aichat.util.MessageValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * AI Chat 流式服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatStreamService {

    private final DeepSeekClient deepSeekClient;
    private final SessionContextService sessionContextService;
    private final MemoryService memoryService;
    private final ChatAsyncProcessingService chatAsyncProcessingService;
    private final AiChatMetrics metrics;
    private final QuotaService quotaService;

    /**
     * 处理流式聊天请求
     */
    public Flux<StreamEvent> handleChatStreamRequest(ChatRequest request) {
        metrics.recordChatRequest();

        String sessionId = ensureConversationId(request);
        String correlationId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        log.info("开始处理流式聊天请求 - customerId: {}, conversationId: {}, correlationId: {}",
                request.getCustomerId(), sessionId, correlationId);

        // 创建累积器
        StreamResponseAccumulator accumulator = new StreamResponseAccumulator();

        // 直接处理流式请求
        return processStreamRequest(request, sessionId, correlationId, startTime)
                .doOnNext(event -> {
                    // 累积每个事件
                    accumulator.accumulate(event);
                })
                .doFinally(signal -> {
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("流式聊天请求处理完成 - customerId: {}, correlationId: {}, 耗时: {}ms",
                            request.getCustomerId(), correlationId, duration);

                    // 如果流式响应完成且无错误，保存完整内容到历史
                    if (accumulator.isCompleted() && !accumulator.hasError()) {
                        saveStreamResponseToHistory(request, accumulator, correlationId);
                        // 更新配额
                        updateQuotaFromStream(request, accumulator, correlationId);
                    }
                })
                .onErrorResume(ex -> {
                    log.error("流式聊天请求处理失败 - customerId: {}, correlationId: {}",
                            request.getCustomerId(), correlationId, ex);
                    return Flux.just(StreamEvent.error(
                            "处理失败: " + ex.getMessage(),
                            1000
                    ));
                });
    }

    /**
     * 保存流式响应到聊天历史
     */
    private void saveStreamResponseToHistory(
            ChatRequest request,
            StreamResponseAccumulator accumulator,
            String correlationId) {
        try {
            String fullContent = accumulator.getFullContent();
            String conversationId = accumulator.getConversationId();

            if (fullContent == null || fullContent.isEmpty()) {
                log.warn("流式响应内容为空，跳过保存 - correlationId: {}", correlationId);
                return;
            }

            // 构建 DifyResponse 用于保存
            DifyResponse response = new DifyResponse();
            response.setAnswer(fullContent);
            response.setConversationId(conversationId);
            response.setMessageId("msg-" + UUID.randomUUID().toString());

            // 如果有 token 使用信息，添加到 metadata
            StreamEvent.TokenUsage tokenUsage = accumulator.getTokenUsage();
            if (tokenUsage != null) {
                DifyResponse.Metadata metadata = new DifyResponse.Metadata();
                metadata.setUsage(String.format("prompt_tokens=%d, completion_tokens=%d, total_tokens=%d",
                        tokenUsage.getPromptTokens(),
                        tokenUsage.getCompletionTokens(),
                        tokenUsage.getTotalTokens()));
                response.setMetadata(metadata);
            }

            // 异步保存到历史
            chatAsyncProcessingService.handleChatCompleted(request, response, correlationId);

            log.info("流式响应已保存到历史 - conversationId: {}, contentLength: {}, correlationId: {}",
                    conversationId, fullContent.length(), correlationId);

        } catch (Exception e) {
            log.error("保存流式响应到历史失败 - correlationId: {}", correlationId, e);
        }
    }

    /**
     * 从流式响应更新配额
     */
    private void updateQuotaFromStream(
            ChatRequest request,
            StreamResponseAccumulator accumulator,
            String correlationId) {
        try {
            StreamEvent.TokenUsage tokenUsage = accumulator.getTokenUsage();
            if (tokenUsage == null) {
                log.warn("流式响应无 token 使用信息，跳过配额更新 - correlationId: {}", correlationId);
                return;
            }

            quotaService.consumeQuota(
                    request.getCustomerId(),
                    accumulator.getConversationId(),
                    "msg-" + UUID.randomUUID().toString(),
                    tokenUsage.getPromptTokens(),
                    tokenUsage.getCompletionTokens()
            );

            log.info("流式响应配额已更新 - customerId: {}, totalTokens: {}, correlationId: {}",
                    request.getCustomerId(), tokenUsage.getTotalTokens(), correlationId);

        } catch (Exception e) {
            log.error("更新流式响应配额失败 - correlationId: {}", correlationId, e);
        }
    }

    private Flux<StreamEvent> processStreamRequest(
            ChatRequest request,
            String sessionId,
            String correlationId,
            long startTime) {

        return Mono.fromCallable(() -> {
            // 检查配额
            int estimatedTokens = 4000;
            quotaService.checkQuota(request.getCustomerId(), estimatedTokens);

            // 获取或创建会话上下文
            SessionContext sessionContext = sessionContextService.getOrCreateContext(
                    sessionId,
                    request.getCustomerId(),
                    request.getGroupId(),
                    request.getMessage()
            );

            log.info("会话上下文已准备 - sessionId: {}, intent: {}, phase: {}, correlationId: {}",
                    sessionId,
                    sessionContext.getUserIntent(),
                    sessionContext.getConversationPhase(),
                    correlationId);

            return sessionContext;
        })
        .flatMapMany(sessionContext -> {
            // 获取用户画像
            return Mono.fromCallable(() ->
                    fetchUserProfile(request.getCustomerId())
            )
            .flatMapMany(profile -> {
                // 加载记忆
                return Mono.fromCallable(() ->
                        loadMemories(request, sessionContext, correlationId)
                )
                .flatMapMany(memories -> {
                    // 调用 AI 流式接口
                    return deepSeekClient.chatStream(request, profile, sessionContext, memories)
                            .doOnNext(event -> {
                                // 累积完整响应用于保存
                                if (event.getType() == StreamEvent.EventType.DONE) {
                                    handleStreamCompleted(request, sessionContext, sessionId);
                                }
                            });
                });
            });
        });
    }

    private UserProfileDTO fetchUserProfile(String customerId) {
        try {
            // TODO: 实现真实的用户画像获取
            return new UserProfileDTO();
        } catch (Exception e) {
            log.warn("获取用户画像失败，使用空画像 - customerId: {}", customerId, e);
            return new UserProfileDTO();
        }
    }

    private List<MemoryItemDTO> loadMemories(
            ChatRequest request,
            SessionContext sessionContext,
            String correlationId) {

        boolean shouldRetrieve = MessageValidator.shouldRetrieveMemories(
                request.getMessage(),
                sessionContext.getComplexity()
        );

        if (!shouldRetrieve) {
            log.info("跳过记忆检索 - correlationId: {}", correlationId);
            return List.of();
        }

        try {
            return memoryService.retrieveMemories(
                    request.getGroupId(),
                    request.getCustomerId(),
                    request.getMessage()
            ).join();
        } catch (Exception e) {
            log.error("记忆检索失败 - correlationId: {}", correlationId, e);
            return List.of();
        }
    }

    private void handleStreamCompleted(
            ChatRequest request,
            SessionContext sessionContext,
            String sessionId) {
        try {
            // 更新会话上下文（实际响应需要在流处理中累积）
            sessionContextService.updateContext(
                    sessionId,
                    request.getMessage(),
                    "" // 流式响应的完整内容需要在外部累积
            );

            log.debug("会话上下文已更新 - sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("更新会话上下文失败 - sessionId: {}", sessionId, e);
        }
    }

    private String ensureConversationId(ChatRequest request) {
        if (request.getConversationId() == null || request.getConversationId().isBlank()) {
            request.setConversationId(UUID.randomUUID().toString());
        }
        return request.getConversationId();
    }
}
