package com.lx.governance.aichat.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.dto.DoctorAssistantTaskRequest;
import com.lx.governance.aichat.client.UserProfileClientFixed;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import com.lx.governance.aichat.entity.AiChatHistory;
import com.lx.governance.aichat.config.ProfileCompletionProperties;
import com.lx.governance.aichat.quota.QuotaRepository;
import com.lx.governance.aichat.quota.QuotaService;
import com.lx.governance.aichat.quota.QuotaType;
import com.lx.governance.aichat.quota.QuotaUsageRepository;
import com.lx.governance.aichat.quota.UserQuota;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;

class AiChatStreamServiceTest {

    @Mock
    private DeepSeekClient deepSeekClient;
    @Mock
    private UserProfileClientFixed userProfileClient;
    @Mock
    private SessionContextService sessionContextService;
    @Mock
    private ChatAsyncProcessingService chatAsyncProcessingService;
    @Mock
    private QuotaService quotaService;
    @Mock
    private LayeredMemoryOrchestrator layeredMemoryOrchestrator;
    @Mock
    private TaskTriggerRouter taskTriggerRouter;
    @Mock
    private MedicalKnowledgeQaService medicalKnowledgeQaService;
    @Mock
    private DoctorAssistantTaskRequestFactory doctorAssistantTaskRequestFactory;
    @Mock
    private DoctorAssistantAgentGateway doctorAssistantAgentGateway;
    @Mock
    private AgentDispatchPolicy agentDispatchPolicy;
    @Mock
    private VisitorIdentityService visitorIdentityService;
    @Mock
    private HighRiskFollowUpChat highRiskFollowUp;

    private AiChatStreamService aiChatStreamService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        var runtime = new AgentConversationRouteRuntime(agentDispatchPolicy, true, 600000L);
        var completion = new com.lx.governance.aichat.config.ProfileCompletionProperties();
        completion.setEnabled(false);
        var degradation = Mockito.mock(com.lx.governance.aichat.degradation.DegradationManager.class);
        var strategy = Mockito.mock(com.lx.governance.aichat.degradation.DegradationStrategyExecutor.class);
        when(degradation.isFeatureEnabled("memory")).thenReturn(true);
        when(strategy.getMemoryRetrievalCount()).thenReturn(5);
        aiChatStreamService = new AiChatStreamService(
                deepSeekClient,
                userProfileClient,
                sessionContextService,
                chatAsyncProcessingService,
                new AiChatMetrics(new SimpleMeterRegistry()),
                quotaService,
                layeredMemoryOrchestrator,
                new ChatTurnPolicy(taskTriggerRouter, medicalKnowledgeQaService, runtime, completion, null, null, null, degradation, strategy),
                doctorAssistantTaskRequestFactory,
                doctorAssistantAgentGateway,
                runtime,
                visitorIdentityService, highRiskFollowUp, new ChatRequestIdempotencyService(
                        Mockito.mock(RedisTemplate.class), new com.fasterxml.jackson.databind.ObjectMapper()));
        when(taskTriggerRouter.route(org.mockito.ArgumentMatchers.any(com.lx.governance.aichat.dto.TaskTriggerContext.class)))
                .thenReturn(TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DIRECT_CHAT)
                .taskType(TaskType.NONE)
                .summary("stream_test_default")
                .build());
        when(medicalKnowledgeQaService.shouldUseKnowledgeQa(any())).thenReturn(false);
        when(visitorIdentityService.ensureCustomerId(any())).thenAnswer(invocation ->
                invocation.<ChatRequest>getArgument(0).getCustomerId());
        when(visitorIdentityService.isVisitor(any())).thenReturn(false);
    }

    @Test
    void rejectedPostProcessingStillCompletesStreamAndPersistsHistory() {
        var history = Mockito.mock(com.lx.governance.aichat.mapper.AiChatHistoryMapper.class);
        var memory = Mockito.mock(MemoryService.class);
        var post = new ChatAsyncProcessingService(history,
                Mockito.mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class), memory,
                task -> { throw new RejectedExecutionException("模拟队列满"); }, visitorIdentityService);
        ReflectionTestUtils.setField(aiChatStreamService, "chatAsyncProcessingService", post);
        when(visitorIdentityService.isVisitor(any())).thenReturn(true);
        when(sessionContextService.getOrCreateContext(any(), any(), any(), any(), any(), any()))
                .thenReturn(SessionContext.builder().conversationId("c").build());
        when(layeredMemoryOrchestrator.loadMemories(any(), any(), any(), any(), any(), eq(false), eq(0)))
                .thenReturn(CompletableFuture.completedFuture(LayeredMemories.builder().build()));
        when(deepSeekClient.chatStream(any(), any(), any(), any(LayeredMemories.class)))
                .thenReturn(Flux.just(StreamEvent.start("c"), StreamEvent.content("回复"),
                        StreamEvent.done("c", new StreamEvent.TokenUsage(10, 5, 15))));
        var request = new ChatRequest();
        request.setConversationId("c");
        request.setCustomerId("u");
        request.setGroupId("g");
        request.setMessage("测试");
        request.setRequestId("r");
        request.setMessageTimestamp(Long.MAX_VALUE);
        var events = aiChatStreamService.handleChatStreamRequest(request).collectList().block(Duration.ofSeconds(3));
        assertEquals(StreamEvent.EventType.DONE, events.get(events.size() - 1).getType());
        assertTrue(request.getMessageTimestamp() < Long.MAX_VALUE);
        verify(quotaService).consumeQuota("u", "c", "r", 10, 5);
        var saved = ArgumentCaptor.forClass(AiChatHistory.class);
        verify(history, Mockito.timeout(1000)).insert(saved.capture());
        assertEquals(10, saved.getValue().getPromptTokens());
        assertEquals(5, saved.getValue().getCompletionTokens());
        assertEquals(15, saved.getValue().getTotalTokens());
        Mockito.verifyNoInteractions(memory);
        verify(sessionContextService).updateContext("c", "测试", "回复", "r", request.getMessageTimestamp());
    }

    @Test
    void pendingReviewSkipsMemoryRetrieval() throws Exception {
        var request = new ChatRequest();
        request.setConversationId("pending-conversation");
        request.setCustomerId("customer-1");
        request.setGroupId("group-1");
        request.setMessage("合成复核请求");
        when(sessionContextService.getOrCreateContext(any(), any(), any(), any(), any(), any()))
                .thenReturn(SessionContext.builder().conversationId("pending-conversation").userIntent("fact_query")
                        .lastAgentPendingTaskId(123L).lastAgentReviewStatus("PENDING_APPROVAL")
                        .lastAgentOutcomeAt(System.currentTimeMillis()).build());
        when(taskTriggerRouter.route(any(com.lx.governance.aichat.dto.TaskTriggerContext.class))).thenReturn(TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT).taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS).build());
        when(agentDispatchPolicy.evaluate(any(), any(), any())).thenReturn(new AgentDispatchPolicy.Decision(true, "allowed"));
        var events = aiChatStreamService.handleChatStreamRequest(request).collectList().block(Duration.ofSeconds(3));
        assertTrue(events.stream().anyMatch(event -> ChatTurnPolicy.PENDING_REPLY.equals(event.getContent())));
        Mockito.verifyNoInteractions(layeredMemoryOrchestrator);
    }

    @Test
    void knowledgeReplySkipsMemoryRetrieval() throws Exception {
        var request = new ChatRequest();
        request.setConversationId("knowledge-conversation");
        request.setCustomerId("customer-1");
        request.setGroupId("group-1");
        request.setMessage("合成知识问题");
        when(sessionContextService.getOrCreateContext(any(), any(), any(), any(), any(), any()))
                .thenReturn(SessionContext.builder().conversationId("knowledge-conversation").userIntent("fact_query").build());
        when(medicalKnowledgeQaService.shouldUseKnowledgeQa(any())).thenReturn(true);
        when(medicalKnowledgeQaService.answer(any(), any())).thenReturn(
                MedicalKnowledgeQaService.QaResult.reply("知识回复", List.of(), false));
        var events = aiChatStreamService.handleChatStreamRequest(request).collectList().block(Duration.ofSeconds(3));
        assertTrue(events.stream().anyMatch(event -> "知识回复".equals(event.getContent())));
        Mockito.verifyNoInteractions(layeredMemoryOrchestrator);
    }

    @Test
    void highRiskRetryRechecksRegistrationInsteadOfReplayingUnconfirmedReply() {
        var request = new ChatRequest();
        request.setConversationId("risk-retry");
        request.setCustomerId("patient");
        request.setMessage("胸痛");
        request.setRequestId("same-risk-request");
        when(highRiskFollowUp.handles("胸痛")).thenReturn(true);
        when(highRiskFollowUp.conversationId(any())).thenReturn("risk-retry");
        when(highRiskFollowUp.reply(any())).thenReturn("当前无法确认随访复核已登记", "已为你登记随访复核");

        var first = aiChatStreamService.handleChatStreamRequest(request).collectList().block(Duration.ofSeconds(3));
        var retried = aiChatStreamService.handleChatStreamRequest(request).collectList().block(Duration.ofSeconds(3));

        assertTrue(first.stream().anyMatch(event -> "当前无法确认随访复核已登记".equals(event.getContent())));
        assertTrue(retried.stream().anyMatch(event -> "已为你登记随访复核".equals(event.getContent())));
        verify(highRiskFollowUp, Mockito.times(2)).reply(any());
    }

    @Test
    void highRiskStreamContainsOnlyPatientReply() {
        ChatRequest request = new ChatRequest();
        request.setMessage("胸痛");
        when(highRiskFollowUp.handles("胸痛")).thenReturn(true);
        when(highRiskFollowUp.conversationId(any())).thenReturn("c1");
        when(highRiskFollowUp.reply(any())).thenReturn("请及时就医。");
        var events = aiChatStreamService.handleChatStreamRequest(request).collectList().block();
        assertEquals(List.of(StreamEvent.EventType.START, StreamEvent.EventType.CONTENT, StreamEvent.EventType.DONE),
                events.stream().map(StreamEvent::getType).toList());
        assertEquals("请及时就医。", events.get(1).getContent());
        Mockito.verifyNoInteractions(deepSeekClient, userProfileClient, doctorAssistantAgentGateway);
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedDirectStreamDeductsActualUsageBeforeDone() {
        RedisTemplate<String, Object> redis = Mockito.mock(RedisTemplate.class);
        ValueOperations<String, Object> values = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var accounts = new QuotaRepository();
        var records = new QuotaUsageRepository();
        accounts.save(UserQuota.builder().customerId("customer").quotaType(QuotaType.DAILY)
                .quotaLimit(100_000).quotaUsed(10).enabled(true).build());
        var realQuotaService = new QuotaService(redis, accounts, records, Mockito.mock(TransactionTemplate.class));
        ReflectionTestUtils.setField(realQuotaService, "quotaEnabled", true);
        ReflectionTestUtils.setField(aiChatStreamService, "quotaService", realQuotaService);
        when(visitorIdentityService.isVisitor("customer")).thenReturn(true);
        when(sessionContextService.getOrCreateContext(any(), any(), any(), any(), any(), any()))
                .thenReturn(SessionContext.builder().conversationId("conversation").build());
        when(layeredMemoryOrchestrator.loadMemories(any(), any(), any(), any(), any(), eq(false), eq(0)))
                .thenReturn(CompletableFuture.completedFuture(LayeredMemories.builder().build()));
        when(deepSeekClient.chatStream(any(), any(), any(), org.mockito.ArgumentMatchers.<LayeredMemories>any()))
                .thenReturn(Flux.just(StreamEvent.start("conversation"), StreamEvent.content("测试回复"),
                        StreamEvent.done("conversation", new StreamEvent.TokenUsage(120, 45, 165))));

        var request = new ChatRequest();
        request.setCustomerId("customer");
        request.setConversationId("conversation");
        request.setGroupId("test-group");
        request.setMessage("测试问题");
        request.setRequestId("same-request");
        var done = aiChatStreamService.handleChatStreamRequest(request)
                .filter(event -> event.getType() == StreamEvent.EventType.DONE)
                .map(event -> {
                    assertEquals(175, realQuotaService.getUserQuota("customer").getQuotaUsed());
                    return event;
                }).next().block(Duration.ofSeconds(3));

        aiChatStreamService.handleChatStreamRequest(request).collectList().block(Duration.ofSeconds(3));
        verify(deepSeekClient).chatStream(any(), any(), any(), org.mockito.ArgumentMatchers.<LayeredMemories>any());
        verify(chatAsyncProcessingService, Mockito.timeout(1000)).handleChatCompleted(any(), any(), any());
        assertEquals(175, realQuotaService.getUserQuota("customer").getQuotaUsed());
        assertEquals(165, done.getTokenUsage().getTotalTokens());
        assertEquals(1, records.findByCustomerId("customer").size());
        assertEquals(165, records.findByCustomerId("customer").get(0).getTotalTokens());
    }

    @Test
    void shouldCaptureIncompleteStreamAndCancellationSeparately() {
        var collector = org.mockito.Mockito.mock(com.lx.governance.aichat.evaluation.ChatEvaluationCollector.class);
        org.springframework.test.util.ReflectionTestUtils.setField(aiChatStreamService, "evaluationCollector", collector);
        when(visitorIdentityService.isVisitor(any())).thenReturn(true);
        when(sessionContextService.getOrCreateContext(any(), any(), any(), any(), any(), any()))
                .thenReturn(SessionContext.builder().conversationId("capture-terminal").build());
        when(deepSeekClient.chatStream(any(), any(), any(), org.mockito.ArgumentMatchers.<LayeredMemories>any()))
                .thenReturn(Flux.just(StreamEvent.content("部分回复")))
                .thenReturn(Flux.concat(Flux.just(StreamEvent.content("部分回复")), Flux.never()));
        ChatRequest request = new ChatRequest();
        request.setConversationId("capture-terminal");
        request.setCustomerId("test-customer");
        request.setGroupId("test-group");
        request.setMessage("测试问题");

        aiChatStreamService.handleChatStreamRequest(request).collectList().block();
        aiChatStreamService.handleChatStreamRequest(request).take(1).collectList().block();

        verify(collector).completeStream(any(), org.mockito.ArgumentMatchers.argThat(accumulator ->
                "stream_incomplete".equals(accumulator.getTerminalReason())), eq("ON_COMPLETE"));
        verify(collector).completeStream(any(), org.mockito.ArgumentMatchers.argThat(accumulator ->
                "client_cancelled".equals(accumulator.getTerminalReason())), eq("CANCEL"));
    }

    @Test
    void shouldCapturePipelineFailureWithoutChangingErrorEvent() {
        var collector = org.mockito.Mockito.mock(com.lx.governance.aichat.evaluation.ChatEvaluationCollector.class);
        org.springframework.test.util.ReflectionTestUtils.setField(aiChatStreamService, "evaluationCollector", collector);
        org.mockito.Mockito.doThrow(new IllegalStateException("配额服务不可用"))
                .when(quotaService).checkQuota(any(), org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.doThrow(new IllegalStateException("采集不可用"))
                .when(collector).completeStream(any(), any(), any());
        ChatRequest request = new ChatRequest();
        request.setConversationId("capture-error");
        request.setCustomerId("test-customer");
        request.setGroupId("test-group");
        request.setMessage("测试问题");

        List<StreamEvent> events = aiChatStreamService.handleChatStreamRequest(request).collectList().block();

        assertEquals(StreamEvent.EventType.ERROR, events.get(0).getType());
        verify(collector).begin(any(ChatRequest.class));
        verify(collector).completeStream(any(), org.mockito.ArgumentMatchers.argThat(accumulator ->
                accumulator.hasError() && "stream_pipeline_error".equals(accumulator.getTerminalReason())), eq("ON_ERROR"));
    }

    @Test
    void shouldUseRewrittenQueryWhenLoadingMemories() {
        SessionContext sessionContext = SessionContext.builder()
                .conversationId("conv-1")
                .customerId("customer-1")
                .groupId("group-1")
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("complex")
                .build();

        when(sessionContextService.getOrCreateContext(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(sessionContext);
        when(userProfileClient.requestUserProfile("customer-1"))
                .thenReturn(CompletableFuture.completedFuture(new UserProfileDTO()));
        when(layeredMemoryOrchestrator.loadMemories(eq("group-1"), eq("customer-1"), eq("那我这种情况晚饭怎么吃？"), any(), any(), eq(true), eq(5)))
                .thenReturn(CompletableFuture.completedFuture(LayeredMemories.builder()
                        .shortTermMemories(List.of("当前话题: 减重管理"))
                        .longTermMemories(List.of(new MemoryItemDTO()))
                        .build()));
        when(deepSeekClient.chatStream(any(), any(), any(),
                org.mockito.ArgumentMatchers.<LayeredMemories>any()))
                .thenReturn(Flux.just(
                        StreamEvent.start("conv-1"),
                        StreamEvent.content("建议清淡饮食"),
                        StreamEvent.done("conv-1", null)));

        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-1");
        request.setCustomerId("customer-1");
        request.setGroupId("group-1");
        request.setMessage("那我这种情况晚饭怎么吃？");

        aiChatStreamService.handleChatStreamRequest(request).collectList().block();

        verify(userProfileClient).requestUserProfile("customer-1");
        verify(layeredMemoryOrchestrator).loadMemories(eq("group-1"), eq("customer-1"), eq("那我这种情况晚饭怎么吃？"), eq(sessionContext), any(UserProfileDTO.class), eq(true), eq(5));
        verify(sessionContextService).updateContext("conv-1", "那我这种情况晚饭怎么吃？", "建议清淡饮食", null, request.getMessageTimestamp());
    }

    @Test
    void shouldPreserveUnavailableProfileWhenProfileFetchFails() {
        SessionContext sessionContext = SessionContext.builder()
                .conversationId("conv-2")
                .customerId("customer-2")
                .groupId("group-2")
                .userIntent("一般咨询")
                .build();

        when(sessionContextService.getOrCreateContext(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(sessionContext);
        CompletableFuture<UserProfileDTO> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("profile fetch failed"));
        when(userProfileClient.requestUserProfile("customer-2")).thenReturn(failed);
        when(layeredMemoryOrchestrator.loadMemories(eq("group-2"), eq("customer-2"), eq("最近睡不好怎么办"), any(), any(), eq(true), eq(5)))
                .thenReturn(CompletableFuture.completedFuture(LayeredMemories.builder().build()));
        when(deepSeekClient.chatStream(any(), any(), any(),
                org.mockito.ArgumentMatchers.<LayeredMemories>any()))
                .thenReturn(Flux.just(
                        StreamEvent.start("conv-2"),
                        StreamEvent.content("先规律作息"),
                        StreamEvent.done("conv-2", null)));

        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-2");
        request.setCustomerId("customer-2");
        request.setGroupId("group-2");
        request.setMessage("最近睡不好怎么办");

        aiChatStreamService.handleChatStreamRequest(request).collectList().block();

        verify(layeredMemoryOrchestrator).loadMemories(eq("group-2"), eq("customer-2"), eq("最近睡不好怎么办"),
                eq(sessionContext), org.mockito.ArgumentMatchers.isNull(), eq(true), eq(5));
        verify(sessionContextService).updateContext("conv-2", "最近睡不好怎么办", "先规律作息", null, request.getMessageTimestamp());
        verify(chatAsyncProcessingService, Mockito.timeout(1000)).handleChatCompleted(any(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "general_health_consultation"})
    void degradedIntentOrProfileDoesNotReplaceStreamWithProfileQuestions(String intent) {
        var policy = ReflectionTestUtils.getField(aiChatStreamService, "policy");
        var completion = (ProfileCompletionProperties) ReflectionTestUtils.getField(policy, "completion");
        completion.setEnabled(true);
        when(sessionContextService.getOrCreateContext(any(), any(), any(), any(), any(), any()))
                .thenReturn(SessionContext.builder().conversationId("degraded-stream").userIntent(intent).build());
        when(userProfileClient.requestUserProfile(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("模拟画像服务不可用")));
        when(layeredMemoryOrchestrator.loadMemories(any(), any(), any(), any(), any(), eq(true), eq(5)))
                .thenReturn(CompletableFuture.completedFuture(LayeredMemories.builder().build()));
        when(deepSeekClient.chatStream(any(), any(), any(), any(LayeredMemories.class)))
                .thenReturn(Flux.just(StreamEvent.start("degraded-stream"), StreamEvent.content("健康档案是健康信息记录。"),
                        StreamEvent.done("degraded-stream", null)));
        var request = new ChatRequest();
        request.setCustomerId("synthetic-patient");
        request.setConversationId("degraded-stream");
        request.setGroupId("synthetic-group");
        request.setMessage("什么是健康档案？");
        var events = aiChatStreamService.handleChatStreamRequest(request).collectList().block(Duration.ofSeconds(3));
        assertEquals("健康档案是健康信息记录。", events.stream()
                .filter(event -> event.getType() == StreamEvent.EventType.CONTENT).findFirst().orElseThrow().getContent());
        assertEquals(StreamEvent.EventType.DONE, events.get(events.size() - 1).getType());
        if ("unknown".equals(intent)) {
            Mockito.verifyNoInteractions(userProfileClient);
        } else {
            verify(userProfileClient).requestUserProfile("synthetic-patient");
        }
    }

    @Test
    void shouldPassThroughPatientVisibleEvidenceEventsWhenAgentStreamSucceeds() {
        SessionContext sessionContext = SessionContext.builder()
                .conversationId("conv-agent-1")
                .customerId("customer-1")
                .groupId("group-1")
                .conversationPhase("follow_up")
                .userIntent("随访分析")
                .complexity("complex")
                .build();

        when(sessionContextService.getOrCreateContext(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(sessionContext);
        when(taskTriggerRouter.route(org.mockito.ArgumentMatchers.any(com.lx.governance.aichat.dto.TaskTriggerContext.class)))
                .thenReturn(TaskTriggerDecision.builder()
                        .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                        .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                        .summary("stream_agent_test")
                        .build());
        when(agentDispatchPolicy.evaluate(any(), any(), any()))
                .thenReturn(new AgentDispatchPolicy.Decision(true, "gray_enabled"));
        when(userProfileClient.requestUserProfile("customer-1"))
                .thenReturn(CompletableFuture.completedFuture(new UserProfileDTO()));
        when(layeredMemoryOrchestrator.loadMemories(eq("group-1"), eq("customer-1"), eq("请帮我分析最近一周血糖变化"), any(), any(), eq(true), eq(5)))
                .thenReturn(CompletableFuture.completedFuture(LayeredMemories.builder().build()));
        when(doctorAssistantTaskRequestFactory.buildPatientDialogRequest(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(DoctorAssistantTaskRequest.builder()
                        .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                        .conversationId("conv-agent-1")
                        .correlationId("corr-agent-1")
                        .build());
        when(doctorAssistantAgentGateway.analyzePatientTaskStream(any()))
                .thenReturn(Flux.just(
                        StreamEvent.status("evidence", "已附带患者可见证据卡片", java.util.Map.of(
                                "count", 1,
                                "items", List.of(java.util.Map.of(
                                        "title", "血糖复盘建议",
                                        "summary", "建议继续记录近 3 天血糖变化。",
                                        "source", "HEALTH_KNOWLEDGE",
                                        "trustLevel", "HIGH")))),
                        StreamEvent.content("建议继续观察并补充近一周血糖记录。"),
                        StreamEvent.done("conv-agent-1", null)));

        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-agent-1");
        request.setCustomerId("customer-1");
        request.setGroupId("group-1");
        request.setMessage("请帮我分析最近一周血糖变化");

        List<StreamEvent> events = aiChatStreamService.handleChatStreamRequest(request).collectList().block();

        assertTrue(events.stream().anyMatch(event ->
                event.getType() == StreamEvent.EventType.STATUS
                        && "evidence".equals(event.getStage())
                        && event.getDetails() != null
                        && Integer.valueOf(1).equals(event.getDetails().get("count"))));
        assertEquals("建议继续观察并补充近一周血糖记录。",
                events.stream()
                        .filter(event -> event.getType() == StreamEvent.EventType.CONTENT)
                        .reduce((first, second) -> second)
                        .orElseThrow()
                        .getContent());
        verify(doctorAssistantAgentGateway).analyzePatientTaskStream(any());
    }
}
