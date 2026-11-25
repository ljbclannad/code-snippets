package com.lx.governance.aichat.service;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.evaluation.ChatEvaluationCollector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import com.lx.governance.aichat.client.UserProfileClientFixed;
import com.lx.governance.aichat.dto.*;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import com.lx.governance.aichat.quota.QuotaService;
import com.lx.governance.aichat.stream.StreamResponseAccumulator;
import com.lx.governance.agentplatform.runtime.PlatformRouteTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.UUID;

/**
 * AI Chat 流式服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatStreamService {

    @Autowired(required = false)
    private ChatEvaluationCollector evaluationCollector;

    private void captureSafely(Runnable action) {
        if (evaluationCollector == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("流式评测采集失败，继续返回业务事件", ex);
        }
    }


    private final DeepSeekClient deepSeekClient;
    private final UserProfileClientFixed userProfileClient;
    private final SessionContextService sessionContextService;
    private final ChatAsyncProcessingService chatAsyncProcessingService;
    private final AiChatMetrics metrics;
    private final QuotaService quotaService;
    private final LayeredMemoryOrchestrator layeredMemoryOrchestrator;
    private final ChatTurnPolicy policy;
    private final DoctorAssistantTaskRequestFactory doctorAssistantTaskRequestFactory;
    private final DoctorAssistantAgentGateway doctorAssistantAgentGateway;
    private final AgentConversationRouteRuntime agentConversationRouteRuntime;
    private final VisitorIdentityService visitorIdentityService;
    private final HighRiskFollowUpChat highRiskFollowUp;
    private final ChatRequestIdempotencyService idempotencyService;

    /**
     * 处理流式聊天请求
     */
    public Flux<StreamEvent> handleChatStreamRequest(ChatRequest request) {
        ChatTurnOrder.begin(request);
        return Flux.defer(() -> {
            ensureConversationId(request);
            return idempotencyService.executeStreamOrReplay(request, () -> executeChatStreamRequest(request));
        });
    }

    private Flux<StreamEvent> executeChatStreamRequest(ChatRequest request) {
        return Flux.defer(() -> {
            // 每次订阅独立保存采集状态，避免重复订阅覆盖同一请求的轨迹。
            ChatRequest subscriptionRequest = new ChatRequest();
            BeanUtils.copyProperties(request, subscriptionRequest, "evaluationCaptureContext");
            captureSafely(() -> evaluationCollector.begin(subscriptionRequest));
            try {
                return handleChatStreamRequestInternal(subscriptionRequest);
            } catch (RuntimeException ex) {
                StreamResponseAccumulator failed = new StreamResponseAccumulator();
                failed.markFailed("stream_initialization_error");
                captureSafely(() -> evaluationCollector.completeStream(subscriptionRequest, failed, "ON_ERROR"));
                return Flux.error(ex);
            }
        });
    }

    private Flux<StreamEvent> handleChatStreamRequestInternal(ChatRequest request) {
        metrics.recordChatRequest();

        if (highRiskFollowUp.handles(request.getMessage())) {
            String sessionId = highRiskFollowUp.conversationId(request);
            StreamResponseAccumulator accumulator = new StreamResponseAccumulator();
            return Flux.concat(Flux.just(StreamEvent.start(sessionId)),
                    Mono.fromCallable(() -> StreamEvent.content(highRiskFollowUp.reply(request)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(RejectedExecutionException.class,
                                    ex -> Mono.just(StreamEvent.content(highRiskFollowUp.unavailable(request)))),
                    Flux.just(StreamEvent.done(sessionId, null)))
                    .doOnNext(event -> {
                        accumulator.accumulate(event);
                        captureSafely(() -> evaluationCollector.event(request, event));
                    })
                    .doFinally(signal -> captureSafely(() ->
                            evaluationCollector.completeStream(request, accumulator, signal.name())));
        }
        String sessionId = ensureConversationId(request);
        String customerId = visitorIdentityService.ensureCustomerId(request);
        if (request.getCustomerId() == null || request.getCustomerId().isBlank()) {
            request.setCustomerId(customerId);
        }
        ChatResponse early = policy.earlyResponse(request);
        if (early != null) {
            return patientReply(sessionId, early.getMessage(), early.getPatientVisibleEvidence());
        }
        String correlationId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        log.info("开始处理流式聊天请求 - customerId: {}, conversationId: {}, correlationId: {}",
                customerId, sessionId, correlationId);

        // 创建累积器
        StreamResponseAccumulator accumulator = new StreamResponseAccumulator();

        // 直接处理流式请求
        return processStreamRequest(request, sessionId, correlationId, startTime)
                .concatMap(event -> {
                    if (event.getType() != StreamEvent.EventType.DONE || event.getTokenUsage() == null
                            || accumulator.isCompleted()) {
                        return Mono.just(event);
                    }
                    // 配额服务包含阻塞 I/O，先结算再发送 DONE，避免快速续聊读到旧余额。
                    return Mono.fromRunnable(() -> updateQuotaFromStream(
                                    request, sessionId, event.getTokenUsage(), correlationId))
                            .thenReturn(event)
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .doOnNext(event -> {
                    // 累积每个事件
                    accumulator.accumulate(event);
                    captureSafely(() -> evaluationCollector.event(request, event));
                    if (event.getType() == StreamEvent.EventType.DONE) {
                        handleStreamCompleted(request, sessionId, accumulator);
                    }
                })
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        accumulator.markInterrupted("client_cancelled");
                    } else if (signal == SignalType.ON_ERROR) {
                        accumulator.markFailed("stream_pipeline_error");
                    }
                    if (signal == SignalType.ON_COMPLETE && !accumulator.isCompleted() && !accumulator.hasError()) {
                        accumulator.markFailed("stream_incomplete");
                    }
                    captureSafely(() -> evaluationCollector.completeStream(request, accumulator, signal.name()));
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("流式聊天请求处理完成 - customerId: {}, correlationId: {}, state: {}, reason: {}, 耗时: {}ms",
                            request.getCustomerId(),
                            correlationId,
                            accumulator.getCompletionState(),
                            accumulator.getTerminalReason(),
                            duration);

                    // 如果流式响应完成且无错误，保存完整内容到历史
                    if (accumulator.isCompleted() && !accumulator.hasError()) {
                        saveStreamResponseToHistory(request, accumulator, correlationId);
                    }
                })
                .onErrorResume(ex -> {
                    log.error("流式聊天请求处理失败 - customerId: {}, correlationId: {}",
                            request.getCustomerId(), correlationId, ex);
                    return Flux.just(StreamEvent.error(
                            ChatTurnPolicy.FAILURE_REPLY,
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
            String conversationId,
            StreamEvent.TokenUsage tokenUsage,
            String correlationId) {
        try {
            quotaService.consumeQuota(
                    request.getCustomerId(),
                    conversationId,
                    request.getRequestId() == null || request.getRequestId().isBlank()
                            ? "msg-" + UUID.randomUUID() : request.getRequestId().trim(),
                    tokenUsage.getPromptTokens(),
                    tokenUsage.getCompletionTokens()
            );
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
            int estimatedTokens = 4000;
            quotaService.checkQuota(request.getCustomerId(), estimatedTokens);

            SessionContext sessionContext = sessionContextService.getOrCreateContext(
                    sessionId,
                    request.getCustomerId(),
                    request.getGroupId(),
                    request.getMessage(),
                    request.getRequestId(),
                    request.getMessageTimestamp()
            );

            log.info("会话上下文已准备 - sessionId: {}, intent: {}, phase: {}, correlationId: {}",
                    sessionId,
                    sessionContext.getUserIntent(),
                    sessionContext.getConversationPhase(),
                    correlationId);

            return sessionContext;
        })
        .flatMapMany(sessionContext -> {
            TaskTriggerDecision triggerDecision = policy.trigger(request, sessionContext);
            sessionContextService.recordTaskTriggerDecision(
                    sessionId,
                    triggerDecision,
                    request.getRequestId(),
                    request.getMessageTimestamp());
            captureSafely(() -> evaluationCollector.context(request, sessionContext, triggerDecision));
            metrics.recordTaskTriggerDecision(triggerDecision);

            boolean visitor = visitorIdentityService.isVisitor(request.getCustomerId());
            boolean loadProfile = policy.loadProfile(visitor, sessionContext);
            UserProfileDTO profile = loadProfile ? fetchUserProfile(request.getCustomerId()) : new UserProfileDTO();
            String question = loadProfile ? policy.profileQuestion(request, sessionContext, profile, triggerDecision) : null;
            if (question != null) {
                return patientReply(sessionId, question, null);
            }
            var plan = policy.decide(request, sessionContext, triggerDecision, visitor);
            var memories = plan.needsMemories()
                    ? loadLayeredMemories(request, sessionContext, profile, correlationId)
                    : LayeredMemories.builder().build();
            return switch (plan.route()) {
                case KNOWLEDGE -> patientReply(sessionId, plan.answer().answer(), plan.answer().patientVisibleEvidence());
                case PENDING -> patientReply(sessionId, ChatTurnPolicy.PENDING_REPLY, null);
                case ERROR -> Flux.just(StreamEvent.error(ChatTurnPolicy.FAILURE_REPLY, 3002));
                case DIRECT, FALLBACK -> directChatStream(request, profile, sessionContext, memories);
                case AGENT -> invokeAgentStream(request, sessionContext, sessionId, correlationId,
                        plan.trigger(), profile, memories);
            };
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<StreamEvent> patientReply(String sessionId, String reply, List<PatientVisibleEvidence> evidence) {
        var start = Flux.just(StreamEvent.start(sessionId));
        if (evidence != null && !evidence.isEmpty()) {
            start = start.concatWithValues(StreamEvent.status("evidence", "已附带患者可见证据卡片",
                    java.util.Map.of("count", evidence.size(), "items", evidence)));
        }
        return start.concatWithValues(StreamEvent.content(reply), StreamEvent.done(sessionId, null));
    }

    private Flux<StreamEvent> invokeAgentStream(
            ChatRequest request,
            SessionContext sessionContext,
            String sessionId,
            String correlationId,
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
        return Flux.concat(
                Flux.just(StreamEvent.start(sessionId)),
                Flux.just(StreamEvent.status(
                        "agent",
                        "正在请医生助手分析你的问题",
                        null)),
                Flux.defer(() -> doctorAssistantAgentGateway.analyzePatientTaskStream(agentTaskRequest))
                        .doOnNext(event -> {
                            var result = event.getEvaluationTaskResponse();
                            if (result != null) {
                                if (agentConversationRouteRuntime.evaluateAgentResponse(result).target() != PlatformRouteTarget.AGENT) {
                                    throw new IllegalStateException("医生助手未返回可用结果");
                                }
                                sessionContextService.recordAgentOutcome(sessionId, result, request.getRequestId(),
                                        request.getMessageTimestamp());
                            }
                        }).onErrorResume(ex -> {
                            if (agentConversationRouteRuntime.fallbackDecision("agent_exception").target() == PlatformRouteTarget.TERMINAL_ERROR) {
                                return Flux.just(StreamEvent.error(ChatTurnPolicy.FAILURE_REPLY, 3002));
                            }
                            return directChatStreamWithoutStart(request, profile, sessionContext, memories);
                        }));
    }

    private Flux<StreamEvent> directChatStream(
            ChatRequest request,
            UserProfileDTO profile,
            SessionContext sessionContext,
            LayeredMemories memories) {
        return deepSeekClient.chatStream(request, profile, sessionContext, memories);
    }

    private Flux<StreamEvent> directChatStreamWithoutStart(
            ChatRequest request,
            UserProfileDTO profile,
            SessionContext sessionContext,
            LayeredMemories memories) {
        return deepSeekClient.chatStream(request, profile, sessionContext, memories)
                .filter(event -> event.getType() != StreamEvent.EventType.START);
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

    private LayeredMemories loadLayeredMemories(
            ChatRequest request,
            SessionContext sessionContext,
            UserProfileDTO userProfile,
            String correlationId) {
        try {
            int count = policy.memoryCount(visitorIdentityService.isVisitor(request.getCustomerId()));
            return layeredMemoryOrchestrator.loadMemories(
                    request.getGroupId(),
                    request.getCustomerId(),
                    request.getMessage(),
                    sessionContext,
                    userProfile,
                    count > 0,
                    count
            ).join();
        } catch (Exception e) {
            log.error("记忆检索失败 - correlationId: {}", correlationId, e);
            return LayeredMemories.builder().build();
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
                    fullContent,
                    request.getRequestId(),
                    request.getMessageTimestamp()
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
