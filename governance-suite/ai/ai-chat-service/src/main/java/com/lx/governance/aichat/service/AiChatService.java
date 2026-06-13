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
import com.lx.governance.aichat.dto.DoctorAssistantTaskRequest;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.dto.DoctorAssistantTaskStatus;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
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
    private final RetrievalQueryRewriteService retrievalQueryRewriteService;
    private final LayeredMemoryOrchestrator layeredMemoryOrchestrator;
    private final TaskTriggerRouter taskTriggerRouter;
    private final DoctorAssistantTaskRequestFactory doctorAssistantTaskRequestFactory;
    private final DoctorAssistantAgentGateway doctorAssistantAgentGateway;
    private final AgentDispatchPolicy agentDispatchPolicy;

    @Value("${ai-chat.chat-history.auto-recommend-enabled:true}")
    private boolean autoRecommendEnabled;

    @Value("${ai-chat.chat-history.recommend-count:3}")
    private int recommendCount;

    @Value("${ai-chat.agent.auto-fallback-to-direct-chat:true}")
    private boolean autoFallbackToDirectChat;

    @Value("${ai-chat.agent.patient-conservative-readonly-mode:true}")
    private boolean patientConservativeReadonlyMode;

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

            TaskTriggerDecision triggerDecision = taskTriggerRouter.route(TaskTriggerContext.builder()
                    .customerId(request.getCustomerId())
                    .groupId(request.getGroupId())
                    .conversationId(sessionId)
                    .message(request.getMessage())
                    .sessionContext(sessionContext)
                    .build());

            log.info("TaskTriggerRouter 判定完成 - conversationId: {}, route: {}, taskType: {}, reasons: {}, summary: {}, correlationId: {}",
                    sessionId,
                    triggerDecision.getRoute(),
                    triggerDecision.getTaskType(),
                    triggerDecision.getReasons(),
                    triggerDecision.getSummary(),
                    correlationId);

            metrics.recordTaskTriggerDecision(triggerDecision);
            sessionContextService.recordTaskTriggerDecision(sessionId, triggerDecision);

            if (triggerDecision.shouldUpgradeToAgent()) {
                metrics.recordAgentUpgradeTriggered();
                log.info("患者对话命中 Agent 升级条件 - conversationId: {}, taskType: {}, correlationId: {}",
                        sessionId,
                        triggerDecision.getTaskType(),
                        correlationId);
            }

            return processChatAsync(request, sessionContext, sessionId, correlationId, startTime, triggerDecision);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(buildErrorResponse(request, correlationId, startTime, e));
        }
    }

    private CompletableFuture<ChatResponse> processChatAsync(
            ChatRequest request,
            SessionContext sessionContext,
            String sessionId,
            String correlationId,
            long startTime,
            TaskTriggerDecision triggerDecision) {

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

            return loadLayeredMemoriesAsync(request, sessionContext, profile, correlationId)
                    .thenCompose(memories -> {
                        log.info("上下文获取完成 - customerId: {}, 短期记忆数: {}, 长期记忆数: {}, correlationId: {}",
                                request.getCustomerId(),
                                memories.getShortTermMemories().size(),
                                memories.getLongTermMemories().size(),
                                correlationId);
                        if (triggerDecision != null && triggerDecision.shouldUpgradeToAgent()) {
                            AgentDispatchPolicy.Decision policyDecision = agentDispatchPolicy.evaluate(request, triggerDecision);
                            if (!policyDecision.invokeAgent()) {
                                log.info("Agent 灰度/熔断策略未放行，回退普通对话 - conversationId: {}, taskType: {}, reason: {}, correlationId: {}",
                                        sessionId,
                                        triggerDecision.getTaskType(),
                                        policyDecision.reason(),
                                        correlationId);
                                return fallbackToDirectChat(
                                        policyDecision.reason(),
                                        request,
                                        sessionContext,
                                        sessionId,
                                        correlationId,
                                        startTime,
                                        profile,
                                        memories);
                            }
                            return invokeAgentOrFallback(
                                    request,
                                    sessionContext,
                                    sessionId,
                                    correlationId,
                                    startTime,
                                    triggerDecision,
                                    profile,
                                    memories);
                        }
                        return CompletableFuture.completedFuture(processDirectChat(
                                request,
                                sessionContext,
                                sessionId,
                                correlationId,
                                startTime,
                                profile,
                                memories));
                    });
        });
    }

    private CompletableFuture<ChatResponse> invokeAgentOrFallback(
            ChatRequest request,
            SessionContext sessionContext,
            String sessionId,
            String correlationId,
            long startTime,
            TaskTriggerDecision triggerDecision,
            UserProfileDTO profile,
            LayeredMemories memories) {
        DoctorAssistantTaskRequest agentTaskRequest = doctorAssistantTaskRequestFactory.buildPatientDialogRequest(
                request,
                sessionContext,
                profile,
                memories,
                triggerDecision,
                correlationId);

        log.info("已完成患者对话 -> Agent 请求打包 - conversationId: {}, taskType: {}, source: {}, hasSessionContext: {}, hasProfileSnapshot: {}, hasMemorySnapshot: {}, correlationId: {}",
                agentTaskRequest.getConversationId(),
                agentTaskRequest.getTaskType(),
                agentTaskRequest.getSource(),
                agentTaskRequest.getSessionContext() != null,
                agentTaskRequest.getProfileSnapshot() != null,
                agentTaskRequest.getMemorySnapshot() != null,
                correlationId);

        long agentStart = System.currentTimeMillis();
        try {
            DoctorAssistantTaskResponse agentResponse = doctorAssistantAgentGateway.analyzePatientTask(agentTaskRequest);
            long durationMs = System.currentTimeMillis() - agentStart;
            String taskType = triggerDecision.getTaskType() != null ? triggerDecision.getTaskType().name() : "UNKNOWN";

            if (agentResponse.getStatus() == DoctorAssistantTaskStatus.SKIPPED) {
                metrics.recordAgentInvocation(taskType, "skipped", durationMs);
                return fallbackToDirectChat(
                        "agent_skipped",
                        request,
                        sessionContext,
                        sessionId,
                        correlationId,
                        startTime,
                        profile,
                        memories);
            }

            if (agentResponse.getStatus() == DoctorAssistantTaskStatus.ERROR
                    || (agentResponse.getPatientSafeReply() == null || agentResponse.getPatientSafeReply().isBlank())) {
                metrics.recordAgentInvocation(taskType, "failure", durationMs);
                return fallbackToDirectChat(
                        "agent_error",
                        request,
                        sessionContext,
                        sessionId,
                        correlationId,
                        startTime,
                        profile,
                        memories);
            }

            metrics.recordAgentInvocation(taskType, "success", durationMs);
            metrics.recordConversationPathLatency("agent", System.currentTimeMillis() - startTime);
            return CompletableFuture.completedFuture(buildAgentChatResponse(sessionId, agentResponse));
        } catch (Exception e) {
            String taskType = triggerDecision.getTaskType() != null ? triggerDecision.getTaskType().name() : "UNKNOWN";
            metrics.recordAgentInvocation(taskType, "failure", System.currentTimeMillis() - agentStart);
            log.error("Agent 调用失败，准备回退普通对话 - conversationId: {}, taskType: {}, correlationId: {}",
                    sessionId, taskType, correlationId, e);
            return fallbackToDirectChat(
                    "agent_exception",
                    request,
                    sessionContext,
                    sessionId,
                    correlationId,
                    startTime,
                    profile,
                    memories);
        }
    }

    private CompletableFuture<ChatResponse> fallbackToDirectChat(
            String reason,
            ChatRequest request,
            SessionContext sessionContext,
            String sessionId,
            String correlationId,
            long startTime,
            UserProfileDTO profile,
            LayeredMemories memories) {
        if (!autoFallbackToDirectChat) {
            return CompletableFuture.completedFuture(ChatResponse.error("Agent 调用失败且未开启普通对话回退"));
        }

        log.info("触发 Agent -> 普通对话回退 - conversationId: {}, reason: {}, correlationId: {}",
                sessionId, reason, correlationId);
        metrics.recordAgentFallback(reason);
        metrics.recordConversationPathLatency("direct_chat_fallback", System.currentTimeMillis() - startTime);
        return CompletableFuture.completedFuture(processDirectChat(
                request,
                sessionContext,
                sessionId,
                correlationId,
                startTime,
                profile,
                memories));
    }

    private ChatResponse processDirectChat(
            ChatRequest request,
            SessionContext sessionContext,
            String sessionId,
            String correlationId,
            long startTime,
            UserProfileDTO profile,
            LayeredMemories memories) {
        DifyResponse difyResponse = deepSeekClient.chat(request, profile, sessionContext, memories);
        metrics.recordConversationPathLatency("direct_chat", System.currentTimeMillis() - startTime);
        return buildChatResponse(
                request,
                sessionContext,
                sessionId,
                correlationId,
                startTime,
                difyResponse
        );
    }

    private ChatResponse buildAgentChatResponse(String sessionId, DoctorAssistantTaskResponse agentResponse) {
        String patientReply = agentResponse.getPatientSafeReply();
        String taskType = agentResponse.getTaskType() != null ? agentResponse.getTaskType().name() : "UNKNOWN";
        if (agentResponse.getStructuredResult() != null
                && agentResponse.getStructuredResult().getUncertainties() != null
                && agentResponse.getStructuredResult().getUncertainties().stream().anyMatch(item -> item != null && item.contains("触发患者侧安全拦截"))) {
            metrics.recordPatientSafetyInterception("PATIENT_TASK_SAFETY_RULE");
        }
        if (agentResponse.getNeedsHumanReview() != null && agentResponse.getNeedsHumanReview()) {
            metrics.recordHumanReviewRequest(taskType);
        }
        if (patientConservativeReadonlyMode && agentResponse.getNeedsHumanReview() != null
                && agentResponse.getNeedsHumanReview()) {
            patientReply = patientReply + "\n\n已同步给医生进一步评估，请先按当前安全建议观察，如有明显不适请及时线下就医。";
        }
        return ChatResponse.success(patientReply, sessionId);
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

    private CompletableFuture<LayeredMemories> loadLayeredMemoriesAsync(
            ChatRequest request,
            SessionContext sessionContext,
            UserProfileDTO profile,
            String correlationId) {
        boolean memoryEnabled = degradationManager.isFeatureEnabled("memory");
        if (!memoryEnabled) {
            log.info("降级模式：跳过长期记忆检索 - level: {}, correlationId: {}",
                    degradationManager.getCurrentLevel(), correlationId);
        }

        int retrievalCount = memoryEnabled ? degradationStrategyExecutor.getMemoryRetrievalCount() : 0;
        return layeredMemoryOrchestrator.loadMemories(
                request.getGroupId(),
                request.getCustomerId(),
                request.getMessage(),
                sessionContext,
                profile,
                memoryEnabled,
                retrievalCount).whenComplete((memories, ex) -> {
                    if (ex == null) {
                        metrics.recordMemoryRetrieval(() -> memories.getLongTermMemories());
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
