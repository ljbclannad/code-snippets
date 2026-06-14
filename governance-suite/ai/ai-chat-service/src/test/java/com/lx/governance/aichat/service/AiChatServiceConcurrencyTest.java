package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.client.UserProfileClientFixed;
import com.lx.governance.aichat.config.ProfileCompletionProperties;
import com.lx.governance.aichat.degradation.DegradationManager;
import com.lx.governance.aichat.degradation.DegradationStrategyExecutor;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.ChatGenerationResult;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.RetrievalQuery;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerReason;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import com.lx.governance.aichat.quota.QuotaService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiChatServiceConcurrencyTest {

    @Mock
    private UserProfileClientFixed userProfileClient;
    @Mock
    private MemoryService memoryService;
    @Mock
    private DeepSeekClient deepSeekClient;
    @Mock
    private ChatHistorySearchService searchService;
    @Mock
    private SessionContextService sessionContextService;
    @Mock
    private ProfileDiagnosisService profileDiagnosisService;
    @Mock
    private FollowUpStateManager followUpStateManager;
    @Mock
    private FollowUpResponseBuilder followUpResponseBuilder;
    @Mock
    private ChatAsyncProcessingService chatAsyncProcessingService;
    @Mock
    private QuotaService quotaService;
    @Mock
    private DegradationManager degradationManager;
    @Mock
    private DegradationStrategyExecutor degradationStrategyExecutor;
    @Mock
    private RetrievalQueryRewriteService retrievalQueryRewriteService;
    @Mock
    private LayeredMemoryOrchestrator layeredMemoryOrchestrator;
    @Mock
    private TaskTriggerRouter taskTriggerRouter;
    @Mock
    private DoctorAssistantTaskRequestFactory doctorAssistantTaskRequestFactory;
    @Mock
    private DoctorAssistantAgentGateway doctorAssistantAgentGateway;
    @Mock
    private AgentDispatchPolicy agentDispatchPolicy;

    private AutoCloseable closeable;
    private ExecutorService executorService;
    private AiChatService aiChatService;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        executorService = Executors.newFixedThreadPool(2);

        ProfileCompletionProperties profileCompletionProperties = new ProfileCompletionProperties();
        profileCompletionProperties.setEnabled(false);

        AiChatMetrics metrics = new AiChatMetrics(new SimpleMeterRegistry());
        ConversationExecutionGuard conversationExecutionGuard = new ConversationExecutionGuard(0L);

        aiChatService = new AiChatService(
                userProfileClient,
                memoryService,
                deepSeekClient,
                searchService,
                sessionContextService,
                metrics,
                profileDiagnosisService,
                followUpStateManager,
                followUpResponseBuilder,
                profileCompletionProperties,
                chatAsyncProcessingService,
                conversationExecutionGuard,
                quotaService,
                degradationManager,
                degradationStrategyExecutor,
                retrievalQueryRewriteService,
                layeredMemoryOrchestrator,
                taskTriggerRouter,
                doctorAssistantTaskRequestFactory,
                doctorAssistantAgentGateway,
                agentDispatchPolicy);

        ReflectionTestUtils.setField(aiChatService, "autoRecommendEnabled", false);
        ReflectionTestUtils.setField(aiChatService, "recommendCount", 3);

        when(userProfileClient.requestUserProfile(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new UserProfileDTO()));
        when(layeredMemoryOrchestrator.loadMemories(anyString(), anyString(), anyString(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(LayeredMemories.builder()
                        .shortTermMemories(Collections.emptyList())
                        .longTermMemories(Collections.emptyList())
                        .build()));
        when(degradationManager.isFeatureEnabled(anyString())).thenReturn(true);
        when(degradationStrategyExecutor.getMemoryRetrievalCount()).thenReturn(5);
        when(retrievalQueryRewriteService.rewrite(anyString(), any(), any()))
                .thenAnswer(invocation -> com.lx.governance.aichat.dto.RetrievalQuery.builder()
                        .originalMessage(invocation.getArgument(0))
                        .rewrittenQuery(invocation.getArgument(0))
                        .rewriteSource("BYPASS")
                        .rewriteReason("test_default")
                        .build());
        when(taskTriggerRouter.route(any())).thenReturn(TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DIRECT_CHAT)
                .taskType(TaskType.NONE)
                .summary("test_default")
                .build());
        when(doctorAssistantTaskRequestFactory.buildPatientDialogRequest(any(), any(), any(), any(), any(), anyString()))
                .thenAnswer(invocation -> com.lx.governance.aichat.dto.DoctorAssistantTaskRequest.builder()
                        .taskType(invocation.<TaskTriggerDecision>getArgument(4).getTaskType())
                        .conversationId(invocation.<ChatRequest>getArgument(0).getConversationId())
                        .correlationId(invocation.getArgument(5))
                        .source(com.lx.governance.aichat.dto.AgentTaskSource.PATIENT_DIALOG_AI_CHAT)
                        .sessionContext(invocation.getArgument(1))
                        .profileSnapshot(invocation.getArgument(2))
                        .memorySnapshot(invocation.getArgument(3))
                        .triggerDecision(invocation.getArgument(4))
                        .build());
        when(doctorAssistantAgentGateway.analyzePatientTask(any())).thenReturn(
                com.lx.governance.aichat.dto.DoctorAssistantTaskResponse.builder()
                        .status(com.lx.governance.aichat.dto.DoctorAssistantTaskStatus.SUCCESS)
                        .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                        .patientSafeReply("建议继续观察并补充近一周血糖记录。")
                        .needsHumanReview(Boolean.FALSE)
                        .build());
        when(agentDispatchPolicy.evaluate(any(), any()))
                .thenReturn(new AgentDispatchPolicy.Decision(true, "gray_enabled"));
        doNothing().when(sessionContextService).recordTaskTriggerDecision(anyString(), any());
        doNothing().when(sessionContextService).updateContext(anyString(), anyString(), anyString());
    }

    @AfterEach
    void tearDown() throws Exception {
        executorService.shutdownNow();
        closeable.close();
    }

    @Test
    void shouldRejectConcurrentRequestForSameConversation() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        SessionContext context = SessionContext.builder()
                .conversationId("conv-1")
                .customerId("customer-1")
                .groupId("group-1")
                .conversationPhase("consulting")
                .userIntent("一般咨询")
                .complexity("simple")
                .build();

        when(sessionContextService.getOrCreateContext(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    firstEntered.countDown();
                    releaseFirst.await(2, TimeUnit.SECONDS);
                    return context;
                });

        when(deepSeekClient.chat(any(), any(), any(),
                org.mockito.ArgumentMatchers.<com.lx.governance.aichat.dto.LayeredMemories>any())).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            ChatGenerationResult response = new ChatGenerationResult();
            response.setAnswer("ok");
            response.setConversationId(request.getConversationId());
            return response;
        });

        ChatRequest request = buildRequest("conv-1");
        CompletableFuture<ChatResponse> firstFuture =
                CompletableFuture.supplyAsync(() -> aiChatService.handleChatRequest(request).join(), executorService);

        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

        ChatResponse secondResponse = aiChatService.handleChatRequest(buildRequest("conv-1")).get(1, TimeUnit.SECONDS);
        assertFalse(secondResponse.getSuccess());
        assertEquals("对话正在处理中，请稍后重试: 当前会话正在处理中，请稍后再试", secondResponse.getErrorMessage());

        releaseFirst.countDown();
        ChatResponse firstResponse = firstFuture.get(2, TimeUnit.SECONDS);
        assertTrue(firstResponse.getSuccess());

        verify(chatAsyncProcessingService, times(1)).handleChatCompleted(any(), any(), anyString());
    }

    @Test
    void shouldAllowNextRequestAfterConversationCompletes() throws Exception {
        SessionContext context = SessionContext.builder()
                .conversationId("conv-2")
                .customerId("customer-1")
                .groupId("group-1")
                .conversationPhase("consulting")
                .userIntent("一般咨询")
                .complexity("simple")
                .build();

        when(sessionContextService.getOrCreateContext(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(context);
        when(deepSeekClient.chat(any(), any(), any(),
                org.mockito.ArgumentMatchers.<com.lx.governance.aichat.dto.LayeredMemories>any())).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            ChatGenerationResult response = new ChatGenerationResult();
            response.setAnswer("ok");
            response.setConversationId(request.getConversationId());
            return response;
        });

        ChatResponse firstResponse = aiChatService.handleChatRequest(buildRequest("conv-2")).get(1, TimeUnit.SECONDS);
        ChatResponse secondResponse = aiChatService.handleChatRequest(buildRequest("conv-2")).get(1, TimeUnit.SECONDS);

        assertTrue(firstResponse.getSuccess());
        assertTrue(secondResponse.getSuccess());
        verify(chatAsyncProcessingService, times(2)).handleChatCompleted(any(), any(), anyString());
    }

    @Test
    void shouldUseRewrittenQueryForMemoryRetrieval() throws Exception {
        SessionContext context = SessionContext.builder()
                .conversationId("conv-3")
                .customerId("customer-1")
                .groupId("group-1")
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .conversationPhase("consulting")
                .complexity("complex")
                .build();

        when(sessionContextService.getOrCreateContext(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(context);
        when(retrievalQueryRewriteService.rewrite(anyString(), any(), any()))
                .thenReturn(RetrievalQuery.builder()
                        .originalMessage("那我这种情况晚饭怎么吃？")
                        .rewrittenQuery("减重管理 饮食建议 晚饭怎么吃")
                        .rewriteSource("RULE")
                        .rewriteReason("context_enriched")
                        .build());
        when(deepSeekClient.chat(any(), any(), any(),
                org.mockito.ArgumentMatchers.<com.lx.governance.aichat.dto.LayeredMemories>any())).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            ChatGenerationResult response = new ChatGenerationResult();
            response.setAnswer("ok");
            response.setConversationId(request.getConversationId());
            return response;
        });
        when(layeredMemoryOrchestrator.loadMemories(eq("group-1"), eq("customer-1"), eq("那我这种情况晚饭怎么吃？"), any(), any(), eq(true), eq(5)))
                .thenReturn(CompletableFuture.completedFuture(LayeredMemories.builder()
                        .shortTermMemories(List.of("当前话题: 减重管理"))
                        .longTermMemories(Collections.emptyList())
                        .build()));

        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-3");
        request.setCustomerId("customer-1");
        request.setGroupId("group-1");
        request.setMessage("那我这种情况晚饭怎么吃？");

        ChatResponse response = aiChatService.handleChatRequest(request).get(1, TimeUnit.SECONDS);

        assertTrue(response.getSuccess());
        verify(layeredMemoryOrchestrator).loadMemories(eq("group-1"), eq("customer-1"), eq("那我这种情况晚饭怎么吃？"), eq(context), any(UserProfileDTO.class), eq(true), eq(5));
    }

    @Test
    void shouldEvaluateTaskTriggerOnNormalChatMainPath() throws Exception {
        SessionContext context = SessionContext.builder()
                .conversationId("conv-4")
                .customerId("customer-1")
                .groupId("group-1")
                .conversationPhase("follow_up")
                .userIntent("随访分析")
                .complexity("complex")
                .build();

        when(sessionContextService.getOrCreateContext(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(context);
        when(taskTriggerRouter.route(any())).thenReturn(TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .reasons(List.of(TaskTriggerReason.EXPLICIT_ANALYSIS_REQUEST))
                .summary("命中随访分析升级条件")
                .build());
        when(deepSeekClient.chat(any(), any(), any(),
                org.mockito.ArgumentMatchers.<com.lx.governance.aichat.dto.LayeredMemories>any())).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            ChatGenerationResult response = new ChatGenerationResult();
            response.setAnswer("已先按普通对话回复");
            response.setConversationId(request.getConversationId());
            return response;
        });

        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-4");
        request.setCustomerId("customer-1");
        request.setGroupId("group-1");
        request.setMessage("请帮我分析一下最近一周的血糖变化");

        ChatResponse response = aiChatService.handleChatRequest(request).get(1, TimeUnit.SECONDS);

        assertTrue(response.getSuccess());
        verify(taskTriggerRouter, atLeastOnce()).route(any());
        verify(sessionContextService).recordTaskTriggerDecision(eq("conv-4"), any(TaskTriggerDecision.class));
        verify(doctorAssistantTaskRequestFactory).buildPatientDialogRequest(eq(request), eq(context), any(UserProfileDTO.class), any(), any(TaskTriggerDecision.class), anyString());
        verify(doctorAssistantAgentGateway).analyzePatientTask(any());
    }

    @Test
    void shouldUpdateContextAndTriggerAsyncProcessingWhenAgentReturnsSuccess() throws Exception {
        SessionContext context = SessionContext.builder()
                .conversationId("conv-5")
                .customerId("customer-1")
                .groupId("group-1")
                .conversationPhase("follow_up")
                .userIntent("随访分析")
                .complexity("complex")
                .build();

        when(sessionContextService.getOrCreateContext(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(context);
        when(taskTriggerRouter.route(any())).thenReturn(TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .reasons(List.of(TaskTriggerReason.EXPLICIT_ANALYSIS_REQUEST))
                .summary("命中随访分析升级条件")
                .build());

        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-5");
        request.setCustomerId("customer-1");
        request.setGroupId("group-1");
        request.setMessage("请帮我分析一下最近一周的体重变化");

        ChatResponse response = aiChatService.handleChatRequest(request).get(1, TimeUnit.SECONDS);

        assertTrue(response.getSuccess());
        assertEquals("建议继续观察并补充近一周血糖记录。", response.getMessage());
        verify(sessionContextService).updateContext("conv-5", "请帮我分析一下最近一周的体重变化", "建议继续观察并补充近一周血糖记录。");
        verify(chatAsyncProcessingService).handleChatCompleted(eq(request), any(ChatGenerationResult.class), anyString());
    }

    private ChatRequest buildRequest(String conversationId) {
        ChatRequest request = new ChatRequest();
        request.setConversationId(conversationId);
        request.setCustomerId("customer-1");
        request.setGroupId("group-1");
        request.setMessage("我头疼");
        return request;
    }
}
