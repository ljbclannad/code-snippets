package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
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
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.metrics.AiChatMetrics;

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
                conversationExecutionGuard);

        ReflectionTestUtils.setField(aiChatService, "autoRecommendEnabled", false);
        ReflectionTestUtils.setField(aiChatService, "recommendCount", 3);

        when(userProfileClient.requestUserProfile(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new UserProfileDTO()));
        when(memoryService.retrieveMemories(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
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

        when(deepSeekClient.chat(any(), any(), any(), anyList())).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            DifyResponse response = new DifyResponse();
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
        assertEquals("当前会话正在处理中，请稍后再试", secondResponse.getErrorMessage());

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
        when(deepSeekClient.chat(any(), any(), any(), anyList())).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            DifyResponse response = new DifyResponse();
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

    private ChatRequest buildRequest(String conversationId) {
        ChatRequest request = new ChatRequest();
        request.setConversationId(conversationId);
        request.setCustomerId("customer-1");
        request.setGroupId("group-1");
        request.setMessage("我头疼");
        return request;
    }
}
