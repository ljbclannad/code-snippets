package com.lx.governance.aichat.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

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
import com.lx.governance.aichat.quota.QuotaService;

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
                visitorIdentityService, highRiskFollowUp);
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
        verify(sessionContextService).updateContext("conv-1", "那我这种情况晚饭怎么吃？", "建议清淡饮食", null, null);
    }

    @Test
    void shouldFallbackToEmptyProfileWhenProfileFetchFails() {
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
                eq(sessionContext), any(UserProfileDTO.class), eq(true), eq(5));
        verify(sessionContextService).updateContext("conv-2", "最近睡不好怎么办", "先规律作息", null, null);
        verify(chatAsyncProcessingService).handleChatCompleted(any(), any(), anyString());
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
