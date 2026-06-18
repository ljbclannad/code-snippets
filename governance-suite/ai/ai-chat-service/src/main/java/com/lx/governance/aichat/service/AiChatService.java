package com.lx.governance.aichat.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
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
import com.lx.governance.aichat.dto.ChatGenerationResult;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.DiagnosisResult;
import com.lx.governance.aichat.dto.DoctorAssistantTaskRequest;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.dto.DoctorAssistantTaskStatus;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.exception.AiServiceException;
import com.lx.governance.aichat.exception.ConversationException;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import com.lx.governance.aichat.quota.QuotaService;
import com.lx.governance.agentplatform.runtime.PlatformRouteDecision;
import com.lx.governance.agentplatform.runtime.PlatformRouteTarget;

import jakarta.annotation.Resource;
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
    private final AgentConversationRouteRuntime agentConversationRouteRuntime;
    @Resource(name = "chatExecutor")
    private Executor chatExecutor;

    @Value("${ai-chat.chat-history.auto-recommend-enabled:true}")
    private boolean autoRecommendEnabled;

    @Value("${ai-chat.chat-history.recommend-count:3}")
    private int recommendCount;

    @Value("${ai-chat.agent.patient-conservative-readonly-mode:true}")
    private boolean patientConservativeReadonlyMode;

    public CompletableFuture<ChatResponse> handleChatRequest(ChatRequest request) {
        // 入口第一步先打总请求指标，便于观察整体吞吐和错误率。
        metrics.recordChatRequest();

        String sessionId = ensureConversationId(request);

        // 如果系统已经进入熔断级降级，直接返回兜底响应，不再进入画像/记忆/模型主链路。
        if (degradationManager.getCurrentLevel() == DegradationLevel.CIRCUIT_BREAK) {
            log.warn("服务熔断中 - customerId: {}", request.getCustomerId());
            return CompletableFuture.completedFuture(
                    degradationStrategyExecutor.getCircuitBreakResponse(sessionId));
        }

        // 在重度降级场景下优先尝试缓存命中，目标是用最低成本快速回复高频重复问题。
        ChatResponse cachedResponse = degradationStrategyExecutor.getCachedResponse(
                request.getCustomerId(), request.getMessage());
        if (cachedResponse != null) {
            return CompletableFuture.completedFuture(cachedResponse);
        }

        // 主链路统一包在响应耗时指标和会话并发保护里，
        // 避免同一 conversation 同时执行多次，并保证整条链路的耗时可观测。
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
            // 在真正消耗画像、记忆和模型资源前先做配额预检，
            // 防止超额请求继续占用后端链路。
            int estimatedTokens = 4000;
            quotaService.checkQuota(request.getCustomerId(), estimatedTokens);

            // SessionContext 是整条链路的第一层上下文来源，
            // 后续路由判定、画像补全和记忆检索都会依赖这里的意图、阶段和摘要信息。
            SessionContext sessionContext = metrics
                    .recordContextAnalysis(() -> sessionContextService.getOrCreateContext(
                            sessionId,
                            request.getCustomerId(),
                            request.getGroupId(),
                            request.getMessage()));
            if (sessionContext.getUserId() == null || sessionContext.getUserId().isBlank()) {
                sessionContext.setUserId(request.getUserId() != null && !request.getUserId().isBlank()
                        ? request.getUserId()
                        : request.getCustomerId());
            }
            if (request.getTenantId() != null && !request.getTenantId().isBlank()) {
                sessionContext.setTenantId(request.getTenantId());
            }

            log.info("会话上下文已准备 - sessionId: {}, intent: {}, phase: {}, correlationId: {}",
                    sessionId,
                    sessionContext.getUserIntent(),
                    sessionContext.getConversationPhase(),
                    correlationId);

            // TaskTriggerRouter 只负责“判定这次是否要升级到医生助手 Agent”，
            // 不直接发起远程调用；它的输出会作为后续 direct chat / agent 分流依据。
            TaskTriggerDecision triggerDecision = taskTriggerRouter.route(TaskTriggerContext.builder()
                    .customerId(request.getCustomerId())
                    .userId(request.getUserId())
                    .groupId(request.getGroupId())
                    .tenantId(request.getTenantId())
                    .conversationId(sessionId)
                    .message(request.getMessage())
                    .sessionContext(sessionContext)
                    .build());

            log.info(
                    "TaskTriggerRouter 判定完成 - conversationId: {}, route: {}, taskType: {}, reasons: {}, summary: {}, correlationId: {}",
                    sessionId,
                    triggerDecision.getRoute(),
                    triggerDecision.getTaskType(),
                    triggerDecision.getReasons(),
                    triggerDecision.getSummary(),
                    correlationId);

            // 这里分别把“最终路由判定”打到指标和会话上下文里：
            // 前者用于观测流量分布，后者用于后续复盘和调试当前会话。
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

        // 先异步拉取用户画像。画像是普通对话、画像补全和记忆重写的共同输入。
        CompletableFuture<UserProfileDTO> profileFuture = userProfileClient
                .requestUserProfile(request.getCustomerId())
                .whenComplete((profile, ex) -> {
                    if (ex == null) {
                        metrics.recordProfileFetch(() -> profile);
                    }
                });

        return profileFuture.thenComposeAsync(profile -> {
            // 如果画像诊断发现关键字段缺失，优先走追问补全链路，
            // 当前轮不会继续执行长期记忆检索或 Agent 分流。
            CompletableFuture<ChatResponse> followUpResponse = handleProfileCompletionIfNeeded(
                    request, sessionContext, profile);
            if (followUpResponse != null) {
                return followUpResponse;
            }

            // 分层记忆加载会同时返回 short-term 和 long-term memories。
            // 其中长期记忆会通过 ai-chat-service -> vector-memory-service 的链路进行向量召回。
            return loadLayeredMemoriesAsync(request, sessionContext, profile, correlationId)
                    .thenComposeAsync(memories -> {
                        log.info("上下文获取完成 - customerId: {}, 短期记忆数: {}, 长期记忆数: {}, correlationId: {}",
                                request.getCustomerId(),
                                memories.getShortTermMemories().size(),
                                memories.getLongTermMemories().size(),
                                correlationId);
                        if (triggerDecision != null && triggerDecision.shouldUpgradeToAgent()) {
                            // 命中 Agent 路由后，还要再经过灰度/熔断/策略放行判断，
                            // 避免医生助手链路在高风险时段被无限放大。
                            PlatformRouteDecision routeDecision =
                                    agentConversationRouteRuntime.evaluateInvocation(request, sessionContext,
                                            triggerDecision);
                            if (!routeDecision.invokeAgent()) {
                                log.info(
                                        "Agent 灰度/熔断策略未放行，回退普通对话 - conversationId: {}, taskType: {}, reason: {}, correlationId: {}",
                                        sessionId,
                                        triggerDecision.getTaskType(),
                                        routeDecision.reason(),
                                        correlationId);
                                return fallbackToDirectChat(
                                        routeDecision.reason(),
                                        request,
                                        sessionContext,
                                        sessionId,
                                        correlationId,
                                        startTime,
                                        profile,
                                        memories);
                            }
                            // 真正的医生助手调用发生在这里：
                            // ai-chat-service 会把当前请求、会话上下文、画像快照和分层记忆打包后发给 doctor-assistant-agent。
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
                        // 没有升级到 Agent 时，直接走普通对话模型回复链路。
                        return CompletableFuture.completedFuture(processDirectChat(
                                request,
                                sessionContext,
                                sessionId,
                                correlationId,
                                startTime,
                                profile,
                                memories));
                    }, chatExecutor);
        }, chatExecutor);
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
        PlatformRouteDecision initialDecision =
                agentConversationRouteRuntime.evaluateInvocation(request, sessionContext, triggerDecision);
        if (initialDecision.target() == PlatformRouteTarget.PENDING_REVIEW_SHORT_CIRCUIT) {
            String patientReply = "这条情况已经同步给医生进一步评估，当前先按之前的安全建议观察；如果出现明显不适或症状加重，请及时线下就医。";
            log.info("检测到会话中已有待处理人工复核，短路跳过重复 Agent 调用 - conversationId: {}, correlationId: {}",
                    sessionId, correlationId);
            sessionContextService.updateContext(sessionId, request.getMessage(), patientReply);
            ChatGenerationResult agentConversationResult = new ChatGenerationResult();
            agentConversationResult.setAnswer(patientReply);
            agentConversationResult.setConversationId(sessionId);
            agentConversationResult.setMessageId("pending-review-short-circuit");
            chatAsyncProcessingService.handleChatCompleted(request, agentConversationResult, correlationId);
            metrics.recordAgentFallback("pending_review_short_circuit");
            return CompletableFuture.completedFuture(ChatResponse.success(patientReply, sessionId));
        }

        // 这里做的是“患者对话 -> 医生助手任务请求”的协议适配，
        // 把 ai-chat-service 已经拿到的上下文快照完整传给 doctor-assistant-agent。
        DoctorAssistantTaskRequest agentTaskRequest = doctorAssistantTaskRequestFactory.buildPatientDialogRequest(
                request,
                sessionContext,
                profile,
                memories,
                triggerDecision,
                correlationId);

        log.info(
                "已完成患者对话 -> Agent 请求打包 - conversationId: {}, taskType: {}, source: {}, hasSessionContext: {}, hasProfileSnapshot: {}, hasMemorySnapshot: {}, correlationId: {}",
                agentTaskRequest.getConversationId(),
                agentTaskRequest.getTaskType(),
                agentTaskRequest.getSource(),
                agentTaskRequest.getSessionContext() != null,
                agentTaskRequest.getProfileSnapshot() != null,
                agentTaskRequest.getMemorySnapshot() != null,
                correlationId);

        long agentStart = System.currentTimeMillis();
        try {
            // 通过 Gateway 发起远程调用，doctor-assistant-agent 会基于这些快照决定是否给出患者可见回复、
            // 是否需要人工复核，以及是否生成医生工作台载荷。
            DoctorAssistantTaskResponse agentResponse = doctorAssistantAgentGateway
                    .analyzePatientTask(agentTaskRequest);
            long durationMs = System.currentTimeMillis() - agentStart;
            String taskType = triggerDecision.getTaskType() != null ? triggerDecision.getTaskType().name() : "UNKNOWN";
            PlatformRouteDecision responseDecision =
                    agentConversationRouteRuntime.evaluateAgentResponse(agentResponse);

            // SKIPPED 表示远端认为当前任务不适合继续走医生助手链路，
            // 这里统一回退普通对话，避免患者端收到空结果。
            if (responseDecision.target() == PlatformRouteTarget.DIRECT_CHAT_FALLBACK
                    && DoctorAssistantTaskStatus.SKIPPED == agentResponse.getStatus()) {
                metrics.recordAgentInvocation(taskType, "skipped", durationMs);
                return fallbackToDirectChat(
                        responseDecision.reason(),
                        request,
                        sessionContext,
                        sessionId,
                        correlationId,
                        startTime,
                        profile,
                        memories);
            }

            // ERROR 或患者可见回复为空时，也要强制回退 direct chat，
            // 否则患者侧无法得到稳定响应。
            if (responseDecision.target() == PlatformRouteTarget.DIRECT_CHAT_FALLBACK) {
                metrics.recordAgentInvocation(taskType, "failure", durationMs);
                return fallbackToDirectChat(
                        responseDecision.reason(),
                        request,
                        sessionContext,
                        sessionId,
                        correlationId,
                        startTime,
                        profile,
                        memories);
            }

            String outcome = agentResponse.getStatus() == DoctorAssistantTaskStatus.PENDING_APPROVAL
                    ? "pending_approval"
                    : "success";
            metrics.recordAgentInvocation(taskType, outcome, durationMs);
            log.info(
                    "Agent 返回可消费结果 - conversationId: {}, taskType: {}, status: {}, needsHumanReview: {}, pendingTaskPresent: {}, correlationId: {}",
                    sessionId,
                    taskType,
                    agentResponse.getStatus(),
                    agentResponse.getNeedsHumanReview(),
                    agentResponse.getPendingTask() != null,
                    correlationId);
            sessionContextService.recordAgentOutcome(sessionId, agentResponse);
            metrics.recordConversationPathLatency("agent", System.currentTimeMillis() - startTime);
            return CompletableFuture.completedFuture(buildAgentChatResponse(
                    request,
                    sessionContext,
                    sessionId,
                    correlationId,
                    agentResponse));
        } catch (Exception e) {
            String taskType = triggerDecision.getTaskType() != null ? triggerDecision.getTaskType().name() : "UNKNOWN";
            metrics.recordAgentInvocation(taskType, "failure", System.currentTimeMillis() - agentStart);
            log.error("Agent 调用失败，准备回退普通对话 - conversationId: {}, taskType: {}, correlationId: {}",
                    sessionId, taskType, correlationId, e);
            return fallbackToDirectChat(
                    agentConversationRouteRuntime.fallbackDecision("agent_exception").reason(),
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
        PlatformRouteDecision fallbackDecision =
                agentConversationRouteRuntime.fallbackDecision(reason);
        if (fallbackDecision.target() == PlatformRouteTarget.TERMINAL_ERROR) {
            return CompletableFuture.completedFuture(ChatResponse.error("Agent 调用失败且未开启普通对话回退"));
        }

        // Agent 调用失败后的回退仍然复用已经准备好的会话上下文、画像和记忆，
        // 避免再次请求后端服务，缩短兜底响应时间。
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
        // 普通对话模型调用使用的是“画像 + SessionContext + 分层记忆”三层上下文，
        // 其中长期记忆已经由 vector-memory-service 召回完成。
        ChatGenerationResult chatResult = deepSeekClient.chat(request, profile, sessionContext, memories);
        metrics.recordConversationPathLatency("direct_chat", System.currentTimeMillis() - startTime);
        return buildChatResponse(
                request,
                sessionContext,
                sessionId,
                correlationId,
                startTime,
                chatResult);
    }

    private ChatResponse buildAgentChatResponse(
            ChatRequest request,
            SessionContext sessionContext,
            String sessionId,
            String correlationId,
            DoctorAssistantTaskResponse agentResponse) {
        String patientReply = agentResponse.getPatientSafeReply();
        String taskType = agentResponse.getTaskType() != null ? agentResponse.getTaskType().name() : "UNKNOWN";

        // 这里消费的是 doctor-assistant-agent 返回的“患者可见结果”。
        // 医生工作台载荷、结构化内部信息不会回灌到患者上下文，避免污染后续对话和记忆抽取。
        if (agentResponse.getStructuredResult() != null
                && agentResponse.getStructuredResult().getUncertainties() != null
                && agentResponse.getStructuredResult().getUncertainties().stream()
                        .anyMatch(item -> item != null && item.contains("触发患者侧安全拦截"))) {
            metrics.recordPatientSafetyInterception("PATIENT_TASK_SAFETY_RULE");
        }
        if (agentResponse.getNeedsHumanReview() != null && agentResponse.getNeedsHumanReview()) {
            metrics.recordHumanReviewRequest(taskType);
        }
        if (patientConservativeReadonlyMode && agentResponse.getNeedsHumanReview() != null
                && agentResponse.getNeedsHumanReview()) {
            patientReply = patientReply + "\n\n已同步给医生进一步评估，请先按当前安全建议观察，如有明显不适请及时线下就医。";
        }
        log.info(
                "准备回传患者安全回复 - sessionId: {}, taskType: {}, status: {}, needsHumanReview: {}, correlationId: {}",
                sessionId,
                taskType,
                agentResponse.getStatus(),
                agentResponse.getNeedsHumanReview(),
                correlationId);

        // Agent 成功分支也要并回患者对话主链路，但仅使用患者可见回复，避免把医生侧内部载荷写入上下文或记忆。
        sessionContextService.updateContext(sessionId, request.getMessage(), patientReply);
        log.debug("Agent 成功后已更新会话上下文 - sessionId: {}, correlationId: {}", sessionId, correlationId);

        // 为了让 Agent 成功分支也能进入统一的聊天后处理链路，
        // 这里把患者可见回复重新封装成 ChatGenerationResult，继续走“保存历史 + 触发记忆提取”。
        ChatGenerationResult agentConversationResult = new ChatGenerationResult();
        agentConversationResult.setAnswer(patientReply);
        agentConversationResult.setConversationId(sessionId);
        agentConversationResult.setMessageId(agentResponse.getTaskId());
        chatAsyncProcessingService.handleChatCompleted(request, agentConversationResult, correlationId);

        return ChatResponse.success(patientReply, sessionId);
    }

    private CompletableFuture<ChatResponse> handleProfileCompletionIfNeeded(
            ChatRequest request,
            SessionContext sessionContext,
            UserProfileDTO profile) {
        if (!profileCompletionProperties.isEnabled()) {
            return null;
        }

        // 画像诊断会基于当前画像 + 会话上下文 + 本轮消息判断是否需要继续追问，
        // 这是主链路里最早的“短路分支”之一。
        DiagnosisResult diagnosis = profileDiagnosisService.diagnoseProfile(
                profile,
                sessionContext,
                request.getMessage());

        if (diagnosis.isNeedsCompletion() &&
                !followUpStateManager.shouldStopFollowUp(sessionContext.getConversationId())) {

            // 命中画像补全时，系统会生成追问问题并写入会话状态，
            // 后续 TaskTriggerRouter 会优先识别这些追问态，避免误升级到 Agent。
            List<String> questions = profileDiagnosisService.generateFollowUpQuestions(
                    diagnosis,
                    profileCompletionProperties.getFollowUp().getMaxQuestionsPerRound());

            List<String> fieldPaths = diagnosis.getMissingFields().stream()
                    .map(DiagnosisResult.MissingField::getFieldPath)
                    .collect(Collectors.toList());

            followUpStateManager.recordFollowUp(
                    sessionContext.getConversationId(),
                    questions,
                    fieldPaths);

            sessionContext.setPendingQuestions(questions);
            sessionContextService.updateContext(
                    sessionContext.getConversationId(),
                    request.getMessage(),
                    "");

            String followUpMessage = followUpResponseBuilder.buildFollowUpMessage(
                    questions,
                    diagnosis);

            // 追问消息也要进入统一聊天历史和后处理链路，
            // 这样后续上下文和记忆抽取能感知本轮系统追问。
            saveFollowUpHistory(request, followUpMessage, sessionContext.getConversationId());

            log.info("画像诊断触发追问 - conversationId: {}, 缺失字段数: {}, 追问问题数: {}",
                    sessionContext.getConversationId(),
                    diagnosis.getMissingFields().size(),
                    questions.size());

            return CompletableFuture.completedFuture(
                    ChatResponse.success(followUpMessage, sessionContext.getConversationId()));
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
        // 长期记忆检索支持被降级策略整体关闭；
        // 关闭时仍然保留 short-term memories，但不会触发 vector-memory-service 召回。
        boolean memoryEnabled = degradationManager.isFeatureEnabled("memory");
        if (!memoryEnabled) {
            log.info("降级模式：跳过长期记忆检索 - level: {}, correlationId: {}",
                    degradationManager.getCurrentLevel(), correlationId);
        }

        int retrievalCount = memoryEnabled ? degradationStrategyExecutor.getMemoryRetrievalCount() : 0;
        // LayeredMemoryOrchestrator 内部会决定：
        // 1. short-term memories 如何从 SessionContext 提取
        // 2. long-term memories 是否值得检索
        // 3. 如果检索，则如何重写 query 并调用 vector-memory-service
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
            ChatGenerationResult chatResult) {
        // direct chat 的成功结果在这里统一收口：
        // 先更新当前会话上下文，再进入异步后处理（保存聊天历史、同步索引、触发记忆提取）。
        sessionContextService.updateContext(sessionId, request.getMessage(), chatResult.getAnswer());
        log.debug("会话上下文已更新 - sessionId: {}, correlationId: {}", sessionId, correlationId);

        chatAsyncProcessingService.handleChatCompleted(request, chatResult, correlationId);

        // 模型调用成功后，再按真实 token 使用量扣减配额；
        // 预检负责兜底，真实扣费在这里收敛。
        try {
            if (chatResult.getMetadata() != null && chatResult.getMetadata().getUsage() != null) {
                String usage = chatResult.getMetadata().getUsage();
                // 解析 token 使用情况（格式: "prompt_tokens=X, completion_tokens=Y, total_tokens=Z"）
                int promptTokens = extractTokenValue(usage, "prompt_tokens");
                int completionTokens = extractTokenValue(usage, "completion_tokens");

                if (promptTokens > 0 && completionTokens > 0) {
                    quotaService.consumeQuota(
                            request.getCustomerId(),
                            chatResult.getConversationId(),
                            chatResult.getMessageId(),
                            promptTokens,
                            completionTokens);
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

        // 主回答已经生成后，才尝试追加相似对话推荐。
        // 推荐链路失败不影响主回答，只是少返回附加信息。
        if (autoRecommendEnabled && degradationManager.isFeatureEnabled("recommendation")) {
            try {
                List<ChatResponse.SimilarConversation> recommendations = getRecommendations(
                        request.getCustomerId(), request.getMessage());

                if (!recommendations.isEmpty()) {
                    log.debug("获取到 {} 条相似对话推荐", recommendations.size());
                    ChatResponse response = ChatResponse.successWithRecommendations(
                            chatResult.getAnswer(),
                            chatResult.getConversationId(),
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

        ChatResponse response = ChatResponse.success(chatResult.getAnswer(), chatResult.getConversationId());

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
                    response);
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
            // 追问场景没有模型生成的完整 metadata，这里只构造最小结果对象，
            // 复用统一的聊天后处理逻辑即可。
            ChatGenerationResult response = new ChatGenerationResult();
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
        // 异步链路里常见 CompletionException 包装，这里先解包，
        // 避免把线程池/CompletableFuture 的包装异常直接暴露给上层。
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
