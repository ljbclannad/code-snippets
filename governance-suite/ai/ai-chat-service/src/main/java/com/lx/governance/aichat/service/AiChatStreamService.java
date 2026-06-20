package com.lx.governance.aichat.service;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.client.UserProfileClientFixed;
import com.lx.governance.aichat.dto.*;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import com.lx.governance.aichat.quota.QuotaService;
import com.lx.governance.aichat.stream.StreamResponseAccumulator;
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
    private final UserProfileClientFixed userProfileClient;
    private final SessionContextService sessionContextService;
    private final MemoryService memoryService;
    private final ChatAsyncProcessingService chatAsyncProcessingService;
    private final AiChatMetrics metrics;
    private final QuotaService quotaService;
    private final LayeredMemoryOrchestrator layeredMemoryOrchestrator;
    private final VisitorIdentityService visitorIdentityService;

    /**
     * 处理流式聊天请求
     */
    public Flux<StreamEvent> handleChatStreamRequest(ChatRequest request) {
        metrics.recordChatRequest();

        String sessionId = ensureConversationId(request);
        String customerId = visitorIdentityService.ensureCustomerId(request);
        String correlationId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        log.info("开始处理流式聊天请求 - customerId: {}, conversationId: {}, correlationId: {}",
                customerId, sessionId, correlationId);

        // 创建累积器
        StreamResponseAccumulator accumulator = new StreamResponseAccumulator();

        // 直接处理流式请求
        return processStreamRequest(request, sessionId, correlationId, startTime)
                .doOnNext(event -> {
                    // 累积每个事件
                    accumulator.accumulate(event);
                    if (event.getType() == StreamEvent.EventType.DONE) {
                        handleStreamCompleted(request, sessionId, accumulator);
                    }
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

            // 构建统一聊天结果用于保存
            ChatGenerationResult response = new ChatGenerationResult();
            response.setAnswer(fullContent);
            response.setConversationId(conversationId);
            response.setMessageId("msg-" + UUID.randomUUID().toString());

            // 如果有 token 使用信息，添加到 metadata
            StreamEvent.TokenUsage tokenUsage = accumulator.getTokenUsage();
            if (tokenUsage != null) {
                ChatGenerationResult.Metadata metadata = new ChatGenerationResult.Metadata();
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
                        loadLayeredMemories(request, sessionContext, profile, correlationId)
                )
                .flatMapMany(memories -> {
                    // 调用 AI 流式接口
                    return deepSeekClient.chatStream(request, profile, sessionContext, memories);
                });
            });
        });
    }

    private UserProfileDTO fetchUserProfile(String customerId) {
        if (visitorIdentityService.isVisitor(customerId)) {
            return new UserProfileDTO();
        }
        try {
            return userProfileClient.requestUserProfile(customerId).join();
        } catch (Exception e) {
            log.warn("获取用户画像失败，使用空画像 - customerId: {}", customerId, e);
            return new UserProfileDTO();
        }
    }

    private com.lx.governance.aichat.dto.LayeredMemories loadLayeredMemories(
            ChatRequest request,
            SessionContext sessionContext,
            UserProfileDTO userProfile,
            String correlationId) {
        try {
            if (visitorIdentityService.isVisitor(request.getCustomerId())) {
                log.debug("visitor 模式跳过流式长期记忆检索 - customerId: {}, correlationId: {}",
                        request.getCustomerId(), correlationId);
                return com.lx.governance.aichat.dto.LayeredMemories.builder().build();
            }
            return layeredMemoryOrchestrator.loadMemories(
                    request.getGroupId(),
                    request.getCustomerId(),
                    request.getMessage(),
                    sessionContext,
                    userProfile,
                    true,
                    5
            ).join();
        } catch (Exception e) {
            log.error("记忆检索失败 - correlationId: {}", correlationId, e);
            return com.lx.governance.aichat.dto.LayeredMemories.builder().build();
        }
    }

    private void handleStreamCompleted(
            ChatRequest request,
            String sessionId,
            StreamResponseAccumulator accumulator) {
        try {
            String fullContent = accumulator.getFullContent();
            sessionContextService.updateContext(
                    sessionId,
                    request.getMessage(),
                    fullContent
            );

            log.debug("会话上下文已更新 - sessionId: {}, assistantLength: {}", sessionId,
                    fullContent != null ? fullContent.length() : 0);
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
