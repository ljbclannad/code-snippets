package com.lx.governance.aichat.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.client.UserProfileClientFixed;
import com.lx.governance.aichat.config.ProfileCompletionProperties;
import com.lx.governance.aichat.config.RabbitMQConfig;
import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.dto.ChatHistorySyncMessage;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.DiagnosisResult;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.entity.AiChatHistory;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import com.lx.governance.aichat.util.MessageValidator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatService {

    private final UserProfileClientFixed userProfileClient;
    private final MemoryService memoryService;
    private final DeepSeekClient deepSeekClient;
    private final AiChatHistoryMapper chatHistoryMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ChatHistorySearchService searchService;
    private final SessionContextService sessionContextService;
    private final AiChatMetrics metrics;
    private final ProfileDiagnosisService profileDiagnosisService;
    private final FollowUpStateManager followUpStateManager;
    private final FollowUpResponseBuilder followUpResponseBuilder;
    private final ProfileCompletionProperties profileCompletionProperties;
    @Qualifier("memoryExtractExecutor")
    private final Executor memoryExtractExecutor;

    @Value("${ai-chat.chat-history.sync-enabled:true}")
    private boolean syncEnabled;

    @Value("${ai-chat.chat-history.auto-recommend-enabled:true}")
    private boolean autoRecommendEnabled;

    @Value("${ai-chat.chat-history.recommend-count:3}")
    private int recommendCount;

    public CompletableFuture<ChatResponse> handleChatRequest(ChatRequest request) {
        // 记录请求
        metrics.recordChatRequest();

        // 包装整个响应时间
        return metrics.recordChatResponseAsync(() -> {
            String correlationId = UUID.randomUUID().toString();
            long startTime = System.currentTimeMillis();

            log.info("开始处理聊天请求 - customerId: {}, groupId: {}, conversationId: {}, message: {}, correlationId: {}",
                    request.getCustomerId(),
                    request.getGroupId(),
                    request.getConversationId(),
                    truncate(request.getMessage(), 50),
                    correlationId);

            try {
                // 1. 获取或创建会话上下文（带监控）
                String sessionId = request.getConversationId() != null ? request.getConversationId()
                        : UUID.randomUUID().toString();

                SessionContext sessionContext = metrics
                        .recordContextAnalysis(() -> sessionContextService.getOrCreateContext(
                                sessionId,
                                request.getCustomerId(),
                                request.getGroupId(),
                                request.getMessage()));

                log.info("会话上下文已准备 - sessionId: {}, intent: {}, phase: {}, correlationId: {}",
                        sessionId,
                        sessionContext.getUserIntent(),
                        sessionContext.getConversationPhase(),
                        correlationId);

                return processChatAsync(request, sessionContext, sessionId, correlationId, startTime)
                        .exceptionally(ex -> {
                            Throwable actual = unwrapCompletionException(ex);
                            long duration = System.currentTimeMillis() - startTime;
                            log.error("聊天请求处理失败 - customerId: {}, correlationId: {}, 耗时: {}ms",
                                    request.getCustomerId(), correlationId, duration, actual);
                            return ChatResponse.error("聊天请求处理失败: " + actual.getMessage());
                        });

            } catch (Exception e) {
                Throwable actual = unwrapCompletionException(e);
                long duration = System.currentTimeMillis() - startTime;
                log.error("聊天请求处理失败 - customerId: {}, correlationId: {}, 耗时: {}ms",
                        request.getCustomerId(), correlationId, duration, actual);
                return CompletableFuture.completedFuture(
                        ChatResponse.error("聊天请求处理失败: " + actual.getMessage())
                );
            }
        });
    }

    private CompletableFuture<ChatResponse> processChatAsync(
            ChatRequest request,
            SessionContext sessionContext,
            String sessionId,
            String correlationId,
            long startTime) {

        CompletableFuture<UserProfileDTO> profileFuture = userProfileClient
                .requestUserProfile(request.getCustomerId())
                .whenComplete((profile, ex) -> {
                    if (ex == null) {
                        metrics.recordProfileFetch(() -> profile);
                    }
                });

        return profileFuture.thenCompose(profile -> {
            CompletableFuture<ChatResponse> followUpResponse = handleProfileCompletionIfNeeded(
                    request, sessionContext, profile);
            if (followUpResponse != null) {
                return followUpResponse;
            }

            return loadMemoriesAsync(request, sessionContext, correlationId)
                    .thenApply(memories -> {
                        log.info("上下文获取完成 - customerId: {}, 记忆数: {}, correlationId: {}",
                                request.getCustomerId(), memories.size(), correlationId);
                        return memories;
                    })
                    .thenApply(memories -> deepSeekClient.chat(request, profile, sessionContext, memories))
                    .thenApply(difyResponse -> buildChatResponse(
                            request,
                            sessionContext,
                            sessionId,
                            correlationId,
                            startTime,
                            difyResponse
                    ));
        });
    }

    private CompletableFuture<ChatResponse> handleProfileCompletionIfNeeded(
            ChatRequest request,
            SessionContext sessionContext,
            UserProfileDTO profile) {
        if (!profileCompletionProperties.isEnabled()) {
            return null;
        }

        DiagnosisResult diagnosis = profileDiagnosisService.diagnoseProfile(
                profile,
                sessionContext,
                request.getMessage()
        );

        if (diagnosis.isNeedsCompletion() &&
                !followUpStateManager.shouldStopFollowUp(sessionContext.getConversationId())) {

            List<String> questions = profileDiagnosisService.generateFollowUpQuestions(
                    diagnosis,
                    profileCompletionProperties.getFollowUp().getMaxQuestionsPerRound()
            );

            List<String> fieldPaths = diagnosis.getMissingFields().stream()
                    .map(DiagnosisResult.MissingField::getFieldPath)
                    .collect(Collectors.toList());

            followUpStateManager.recordFollowUp(
                    sessionContext.getConversationId(),
                    questions,
                    fieldPaths
            );

            sessionContext.setPendingQuestions(questions);
            sessionContextService.updateContext(
                    sessionContext.getConversationId(),
                    request.getMessage(),
                    ""
            );

            String followUpMessage = followUpResponseBuilder.buildFollowUpMessage(
                    questions,
                    diagnosis
            );

            saveFollowUpHistory(request, followUpMessage, sessionContext.getConversationId());

            log.info("画像诊断触发追问 - conversationId: {}, 缺失字段数: {}, 追问问题数: {}",
                    sessionContext.getConversationId(),
                    diagnosis.getMissingFields().size(),
                    questions.size());

            return CompletableFuture.completedFuture(
                    ChatResponse.success(followUpMessage, sessionContext.getConversationId())
            );
        }

        if (!diagnosis.isNeedsCompletion()) {
            followUpStateManager.markCompleted(sessionContext.getConversationId());
        }

        return null;
    }

    private CompletableFuture<List<MemoryItemDTO>> loadMemoriesAsync(
            ChatRequest request,
            SessionContext sessionContext,
            String correlationId) {
        boolean shouldRetrieve = MessageValidator.shouldRetrieveMemories(
                request.getMessage(),
                sessionContext.getComplexity());

        if (!shouldRetrieve) {
            log.info("跳过记忆检索 - message: {}, complexity: {}, correlationId: {}",
                    request.getMessage(), sessionContext.getComplexity(), correlationId);
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }

        log.debug("执行记忆检索 - message: {}, complexity: {}, correlationId: {}",
                request.getMessage(), sessionContext.getComplexity(), correlationId);
        return memoryService.retrieveMemories(
                request.getGroupId(),
                request.getCustomerId(),
                request.getMessage()).whenComplete((memories, ex) -> {
                    if (ex == null) {
                        metrics.recordMemoryRetrieval(() -> memories);
                    }
                });
    }

    private ChatResponse buildChatResponse(
            ChatRequest request,
            SessionContext sessionContext,
            String sessionId,
            String correlationId,
            long startTime,
            DifyResponse difyResponse) {
        sessionContextService.updateContext(sessionId, request.getMessage(), difyResponse.getAnswer());
        log.debug("会话上下文已更新 - sessionId: {}, correlationId: {}", sessionId, correlationId);

        saveConversationHistory(request, difyResponse);
        triggerMemoryExtractionAsync(request, difyResponse, correlationId);

        long duration = System.currentTimeMillis() - startTime;
        log.info("聊天请求处理完成 - customerId: {}, correlationId: {}, 耗时: {}ms",
                request.getCustomerId(), correlationId, duration);

        if (autoRecommendEnabled) {
            try {
                List<ChatResponse.SimilarConversation> recommendations = getRecommendations(
                        request.getCustomerId(), request.getMessage());

                if (!recommendations.isEmpty()) {
                    log.debug("获取到 {} 条相似对话推荐", recommendations.size());
                    return ChatResponse.successWithRecommendations(
                            difyResponse.getAnswer(),
                            difyResponse.getConversationId(),
                            recommendations);
                }
            } catch (Exception e) {
                log.error("获取推荐失败 - customerId: {}, correlationId: {}",
                        request.getCustomerId(), correlationId, e);
            }
        }

        return ChatResponse.success(difyResponse.getAnswer(), difyResponse.getConversationId());
    }

    private void triggerMemoryExtractionAsync(ChatRequest request, DifyResponse difyResponse, String correlationId) {
        CompletableFuture.runAsync(() -> {
            try {
                memoryService.extractMemory(
                        request.getGroupId(),
                        request.getCustomerId(),
                        request.getMessage(),
                        difyResponse.getAnswer());
            } catch (Exception e) {
                log.error("记忆提取触发失败 - customerId: {}, correlationId: {}",
                        request.getCustomerId(), correlationId, e);
            }
        }, memoryExtractExecutor);
    }

    private Throwable unwrapCompletionException(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    /**
     * 保存对话历史到数据库
     */
    private void saveConversationHistory(ChatRequest request, DifyResponse response) {
        try {
            // 保存一轮完整的对话（用户消息 + AI回复）
            AiChatHistory history = new AiChatHistory();
            history.setCustomerId(request.getCustomerId());
            history.setConversationId(response.getConversationId());
            history.setUserMessage(request.getMessage());
            history.setAiResponse(response.getAnswer());
            // TODO: 从 response 中提取 token 使用信息
            history.setPromptTokens(null);
            history.setCompletionTokens(null);
            history.setTotalTokens(null);
            history.setCreateTime(LocalDateTime.now());
            history.setIsDeleted(0);

            chatHistoryMapper.insert(history);

            log.debug("对话历史已保存 - conversationId: {}, id: {}", response.getConversationId(), history.getId());

            // 异步同步到 ES
            if (syncEnabled) {
                syncToElasticsearch(history);
            }

        } catch (Exception e) {
            log.error("保存对话历史失败 - conversationId: {}", request.getConversationId(), e);
            // 不影响主流程，只记录错误
        }
    }

    /**
     * 异步同步对话历史到 Elasticsearch
     */
    private void syncToElasticsearch(AiChatHistory history) {
        try {
            ChatHistorySyncMessage message = ChatHistorySyncMessage.builder()
                    .id(history.getId())
                    .customerId(history.getCustomerId())
                    .conversationId(history.getConversationId())
                    .userMessage(history.getUserMessage())
                    .aiResponse(history.getAiResponse())
                    .promptTokens(history.getPromptTokens())
                    .completionTokens(history.getCompletionTokens())
                    .totalTokens(history.getTotalTokens())
                    .createTime(history.getCreateTime())
                    .build();

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.CHAT_HISTORY_SYNC_EXCHANGE,
                    RabbitMQConfig.CHAT_HISTORY_SYNC_ROUTING_KEY,
                    message);

            log.debug("对话历史同步消息已发送 - id: {}", history.getId());

        } catch (Exception e) {
            log.error("发送对话历史同步消息失败 - id: {}", history.getId(), e);
            // 不影响主流程，只记录错误
        }
    }

    /**
     * 获取相似对话推荐
     */
    private List<ChatResponse.SimilarConversation> getRecommendations(String customerId, String message) {
        try {
            List<ChatHistoryDocument> similarDocs = searchService.findSimilarConversations(
                    customerId, message, recommendCount);

            return similarDocs.stream()
                    .map(doc -> ChatResponse.SimilarConversation.builder()
                            .id(doc.getId())
                            .userMessage(doc.getUserMessage())
                            .aiResponse(doc.getAiResponse())
                            .createTime(doc.getCreateTime())
                            .score(null) // ES 查询可以返回相关性评分，这里暂时为 null
                            .build())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("获取相似对话推荐失败 - customerId: {}", customerId, e);
            return List.of();
        }
    }

    /**
     * 保存追问对话历史
     */
    private void saveFollowUpHistory(ChatRequest request, String followUpMessage, String conversationId) {
        try {
            AiChatHistory history = new AiChatHistory();
            history.setCustomerId(request.getCustomerId());
            history.setConversationId(conversationId);
            history.setUserMessage(request.getMessage());
            history.setAiResponse(followUpMessage);
            history.setPromptTokens(0);  // 追问不消耗 Token
            history.setCompletionTokens(0);
            history.setTotalTokens(0);
            history.setCreateTime(LocalDateTime.now());
            history.setIsDeleted(0);

            chatHistoryMapper.insert(history);

            log.debug("追问对话历史已保存 - conversationId: {}", conversationId);

        } catch (Exception e) {
            log.error("保存追问对话历史失败 - conversationId: {}", conversationId, e);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
