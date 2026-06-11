package com.lx.governance.aichat.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.client.UserProfileClientFixed;
import com.lx.governance.aichat.config.ProfileCompletionProperties;
import com.lx.governance.aichat.degradation.DegradationLevel;
import com.lx.governance.aichat.degradation.DegradationManager;
import com.lx.governance.aichat.degradation.DegradationStrategyExecutor;
import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.DiagnosisResult;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.exception.AiServiceException;
import com.lx.governance.aichat.exception.ConversationException;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import com.lx.governance.aichat.quota.QuotaService;
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
    private final ChatHistorySearchService searchService;
    private final SessionContextService sessionContextService;
    private final AiChatMetrics metrics;
    private final ProfileDiagnosisService profileDiagnosisService;
    private final FollowUpStateManager followUpStateManager;
    private final FollowUpResponseBuilder followUpResponseBuilder;
    private final ProfileCompletionProperties profileCompletionProperties;
    private final ChatAsyncProcessingService chatAsyncProcessingService;
    private final ConversationExecutionGuard conversationExecutionGuard;
    private final QuotaService quotaService;
    private final DegradationManager degradationManager;
    private final DegradationStrategyExecutor degradationStrategyExecutor;

    @Value("${ai-chat.chat-history.auto-recommend-enabled:true}")
    private boolean autoRecommendEnabled;

    @Value("${ai-chat.chat-history.recommend-count:3}")
    private int recommendCount;

    public CompletableFuture<ChatResponse> handleChatRequest(ChatRequest request) {
        // 记录请求
        metrics.recordChatRequest();

        String sessionId = ensureConversationId(request);

        // 检查是否熔断
        if (degradationManager.getCurrentLevel() == DegradationLevel.CIRCUIT_BREAK) {
            log.warn("服务熔断中 - customerId: {}", request.getCustomerId());
            return CompletableFuture.completedFuture(
                    degradationStrategyExecutor.getCircuitBreakResponse(sessionId)
            );
        }

        // 尝试使用缓存响应（极限降级时）
        ChatResponse cachedResponse = degradationStrategyExecutor.getCachedResponse(
                request.getCustomerId(), request.getMessage()
        );
        if (cachedResponse != null) {
            return CompletableFuture.completedFuture(cachedResponse);
        }

        // 包装整个响应时间
        return metrics.recordChatResponseAsync(() -> {
            String correlationId = UUID.randomUUID().toString();
            long startTime = System.currentTimeMillis();

            log.info("开始处理聊天请求 - customerId: {}, groupId: {}, conversationId: {}, message: {}, correlationId: {}",
                    request.getCustomerId(),
                    request.getGroupId(),
                    sessionId,
                    truncate(request.getMessage(), 50),
                    correlationId);

            return conversationExecutionGuard.tryExecute(sessionId, () -> processConversationRequest(
                    request, sessionId, correlationId, startTime))
                    .exceptionally(ex -> buildErrorResponse(request, correlationId, startTime, ex));
        });
    }

    private CompletableFuture<ChatResponse> processConversationRequest(
            ChatRequest request,
            String sessionId,
            String correlationId,
            long startTime) {
        try {
            // 预估需要的 tokens（基于历史平均值或固定值）
            // 这里使用一个保守的估计值
            int estimatedTokens = 4000;
            quotaService.checkQuota(request.getCustomerId(), estimatedTokens);

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

            return processChatAsync(request, sessionContext, sessionId, correlationId, startTime);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(buildErrorResponse(request, correlationId, startTime, e));
        }
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

        // 检查降级：是否启用记忆检索
        if (!degradationManager.isFeatureEnabled("memory")) {
            log.info("降级模式：跳过记忆检索 - level: {}, correlationId: {}",
                    degradationManager.getCurrentLevel(), correlationId);
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }

        boolean shouldRetrieve = MessageValidator.shouldRetrieveMemories(
                request.getMessage(),
                sessionContext.getComplexity());

        if (!shouldRetrieve) {
            log.info("跳过记忆检索 - message: {}, complexity: {}, correlationId: {}",
                    request.getMessage(), sessionContext.getComplexity(), correlationId);
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }

        // 根据降级级别调整检索数量
        int retrievalCount = degradationStrategyExecutor.getMemoryRetrievalCount();
        if (retrievalCount == 0) {
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }

        log.debug("执行记忆检索 - message: {}, complexity: {}, count: {}, correlationId: {}",
                request.getMessage(), sessionContext.getComplexity(), retrievalCount, correlationId);
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

        chatAsyncProcessingService.handleChatCompleted(request, difyResponse, correlationId);

        // 从响应中提取 token 使用情况并更新配额
        try {
            if (difyResponse.getMetadata() != null && difyResponse.getMetadata().getUsage() != null) {
                String usage = difyResponse.getMetadata().getUsage();
                // 解析 token 使用情况（格式: "prompt_tokens=X, completion_tokens=Y, total_tokens=Z"）
                int promptTokens = extractTokenValue(usage, "prompt_tokens");
                int completionTokens = extractTokenValue(usage, "completion_tokens");

                if (promptTokens > 0 && completionTokens > 0) {
                    quotaService.consumeQuota(
                            request.getCustomerId(),
                            difyResponse.getConversationId(),
                            difyResponse.getMessageId(),
                            promptTokens,
                            completionTokens
                    );
                }
            }
        } catch (Exception e) {
            log.error("更新配额失败 - customerId: {}, correlationId: {}",
                    request.getCustomerId(), correlationId, e);
            // 配额更新失败不影响主流程
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("聊天请求处理完成 - customerId: {}, correlationId: {}, 耗时: {}ms",
                request.getCustomerId(), correlationId, duration);

        // 检查是否需要相似对话推荐（降级时可能关闭）
        if (autoRecommendEnabled && degradationManager.isFeatureEnabled("recommendation")) {
            try {
                List<ChatResponse.SimilarConversation> recommendations = getRecommendations(
                        request.getCustomerId(), request.getMessage());

                if (!recommendations.isEmpty()) {
                    log.debug("获取到 {} 条相似对话推荐", recommendations.size());
                    ChatResponse response = ChatResponse.successWithRecommendations(
                            difyResponse.getAnswer(),
                            difyResponse.getConversationId(),
                            recommendations);

                    // 添加降级提示
                    addDegradationNotice(response);

                    // 缓存响应（降级时）
                    cacheResponseIfNeeded(request, response);

                    return response;
                }
            } catch (Exception e) {
                log.error("获取推荐失败 - customerId: {}, correlationId: {}",
                        request.getCustomerId(), correlationId, e);
            }
        }

        ChatResponse response = ChatResponse.success(difyResponse.getAnswer(), difyResponse.getConversationId());

        // 添加降级提示
        addDegradationNotice(response);

        // 缓存响应（降级时）
        cacheResponseIfNeeded(request, response);

        return response;
    }

    /**
     * 添加降级提示到响应
     */
    private void addDegradationNotice(ChatResponse response) {
        String notice = degradationStrategyExecutor.getDegradationNotice();
        if (notice != null && response.getMessage() != null) {
            response.setMessage(response.getMessage() + notice);
        }
    }

    /**
     * 在降级时缓存响应
     */
    private void cacheResponseIfNeeded(ChatRequest request, ChatResponse response) {
        if (degradationManager.getCurrentLevel() == DegradationLevel.CRITICAL) {
            degradationStrategyExecutor.cacheResponse(
                    request.getCustomerId(),
                    request.getMessage(),
                    response
            );
        }
    }

    private Throwable unwrapCompletionException(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
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
            DifyResponse response = new DifyResponse();
            response.setAnswer(followUpMessage);
            response.setConversationId(conversationId);
            chatAsyncProcessingService.handleChatCompleted(request, response, "follow-up");

        } catch (Exception e) {
            log.error("保存追问对话历史失败 - conversationId: {}", conversationId, e);
        }
    }

    private String ensureConversationId(ChatRequest request) {
        if (request.getConversationId() == null || request.getConversationId().isBlank()) {
            request.setConversationId(UUID.randomUUID().toString());
        }
        return request.getConversationId();
    }

    private ChatResponse buildErrorResponse(
            ChatRequest request,
            String correlationId,
            long startTime,
            Throwable throwable) {
        Throwable actual = unwrapCompletionException(throwable);
        long duration = System.currentTimeMillis() - startTime;
        log.error("聊天请求处理失败 - customerId: {}, correlationId: {}, 耗时: {}ms",
                request.getCustomerId(), correlationId, duration, actual);

        if (actual instanceof ConversationException) {
            ConversationException ex = (ConversationException) actual;
            return ChatResponse.error(ex.getErrorCode().getMessage() +
                    (ex.getDetail() != null ? ": " + ex.getDetail() : ""));
        }

        if (actual instanceof AiServiceException) {
            AiServiceException ex = (AiServiceException) actual;
            return ChatResponse.error(ex.getErrorCode().getMessage() +
                    (ex.getDetail() != null ? ": " + ex.getDetail() : ""));
        }

        return ChatResponse.error("聊天请求处理失败: " + actual.getMessage());
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

    /**
     * 从 usage 字符串中提取 token 值
     */
    private int extractTokenValue(String usage, String key) {
        try {
            String pattern = key + "=(\\d+)";
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(usage);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception e) {
            log.warn("解析 token 值失败 - key: {}, usage: {}", key, usage, e);
        }
        return 0;
    }
}
