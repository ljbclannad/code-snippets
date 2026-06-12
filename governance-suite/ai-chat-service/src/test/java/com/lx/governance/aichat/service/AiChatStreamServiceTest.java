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
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import com.lx.governance.aichat.quota.QuotaService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;

class AiChatStreamServiceTest {

    @Mock
    private DeepSeekClient deepSeekClient;
    @Mock
    private SessionContextService sessionContextService;
    @Mock
    private MemoryService memoryService;
    @Mock
    private ChatAsyncProcessingService chatAsyncProcessingService;
    @Mock
    private QuotaService quotaService;
    @Mock
    private LayeredMemoryOrchestrator layeredMemoryOrchestrator;

    private AiChatStreamService aiChatStreamService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        aiChatStreamService = new AiChatStreamService(
                deepSeekClient,
                sessionContextService,
                memoryService,
                chatAsyncProcessingService,
                new AiChatMetrics(new SimpleMeterRegistry()),
                quotaService,
                layeredMemoryOrchestrator);
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

        verify(layeredMemoryOrchestrator).loadMemories(eq("group-1"), eq("customer-1"), eq("那我这种情况晚饭怎么吃？"), eq(sessionContext), any(UserProfileDTO.class), eq(true), eq(5));
    }
}
