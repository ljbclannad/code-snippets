package com.lx.governance.aichat.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.lx.governance.aichat.client.DeepSeekClient;
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
    private DoctorAssistantTaskRequestFactory doctorAssistantTaskRequestFactory;
    @Mock
    private DoctorAssistantAgentGateway doctorAssistantAgentGateway;
    @Mock
    private AgentDispatchPolicy agentDispatchPolicy;
    @Mock
    private VisitorIdentityService visitorIdentityService;

    private AiChatStreamService aiChatStreamService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        aiChatStreamService = new AiChatStreamService(
                deepSeekClient,
                userProfileClient,
                sessionContextService,
                chatAsyncProcessingService,
                new AiChatMetrics(new SimpleMeterRegistry()),
                quotaService,
                layeredMemoryOrchestrator,
                taskTriggerRouter,
                doctorAssistantTaskRequestFactory,
                doctorAssistantAgentGateway,
                new AgentConversationRouteRuntime(agentDispatchPolicy, true, 10 * 60 * 1000L),
                visitorIdentityService);
        when(taskTriggerRouter.route(org.mockito.ArgumentMatchers.any(com.lx.governance.aichat.dto.TaskTriggerContext.class)))
                .thenReturn(TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DIRECT_CHAT)
                .taskType(TaskType.NONE)
                .summary("stream_test_default")
                .build());
        when(visitorIdentityService.ensureCustomerId(any())).thenAnswer(invocation ->
                invocation.<ChatRequest>getArgument(0).getCustomerId());
        when(visitorIdentityService.isVisitor(any())).thenReturn(false);
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

        when(sessionContextService.getOrCreateContext(anyString(), anyString(), anyString(), anyString()))
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
        verify(sessionContextService).updateContext("conv-1", "那我这种情况晚饭怎么吃？", "建议清淡饮食");
    }

    @Test
    void shouldFallbackToEmptyProfileWhenProfileFetchFails() {
        SessionContext sessionContext = SessionContext.builder()
                .conversationId("conv-2")
                .customerId("customer-2")
                .groupId("group-2")
                .userIntent("一般咨询")
                .build();

        when(sessionContextService.getOrCreateContext(anyString(), anyString(), anyString(), anyString()))
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
        verify(sessionContextService).updateContext("conv-2", "最近睡不好怎么办", "先规律作息");
        verify(chatAsyncProcessingService).handleChatCompleted(any(), any(), anyString());
    }
}
