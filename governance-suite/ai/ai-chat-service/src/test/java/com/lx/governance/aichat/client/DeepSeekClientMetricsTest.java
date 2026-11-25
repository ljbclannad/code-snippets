package com.lx.governance.aichat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.context.ContextBudgetManager;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.agentplatform.runtime.PlatformMetricsService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class DeepSeekClientMetricsTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private StreamingChatModel streamingChatModel;

    @Mock
    private ContextBudgetManager contextBudgetManager;

    @Mock
    private com.lx.governance.aichat.monitor.TokenMonitor tokenMonitor;

    private DeepSeekClient deepSeekClient;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        deepSeekClient = new DeepSeekClient(
                chatModel,
                streamingChatModel,
                contextBudgetManager,
                tokenMonitor,
                properties);
        meterRegistry = new SimpleMeterRegistry();
        deepSeekClient.setPlatformMetricsService(new PlatformMetricsService(meterRegistry));
        ReflectionTestUtils.setField(deepSeekClient, "model", "deepseek-chat");
        ReflectionTestUtils.setField(deepSeekClient, "promptPricePer1kTokens", 0.5D);
        ReflectionTestUtils.setField(deepSeekClient, "completionPricePer1kTokens", 1.0D);
        when(contextBudgetManager.buildBudgetedSystemPrompt(any(), any(), any(LayeredMemories.class), any(), any(), any()))
                .thenReturn("system");
    }

    @Test
    void shouldRecordDirectChatTokenCostToPlatformMetrics() {
        when(chatModel.call(any(Prompt.class))).thenReturn(successResponse("ok", 100, 50, 150));

        ChatRequest request = new ChatRequest();
        request.setCustomerId("C001");
        request.setConversationId("conv-1");
        request.setMessage("hello");

        var result = deepSeekClient.chat(request, null, null, LayeredMemories.builder().build());
        assertEquals(100, result.getMetadata().getTokenUsage().promptTokens());
        assertEquals(50, result.getMetadata().getTokenUsage().completionTokens());
        assertEquals(150, result.getMetadata().getTokenUsage().totalTokens());
        assertEquals("prompt_tokens=100, completion_tokens=50, total_tokens=150", result.getMetadata().getUsage());

        assertEquals(150.0, meterRegistry.get("platform.tokens.total")
                .tag("scope", "DIRECT_CHAT")
                .summary().totalAmount());
        assertEquals(0.1D, meterRegistry.get("platform.cost.estimated")
                .tag("scope", "DIRECT_CHAT")
                .summary().totalAmount(), 0.0001D);
    }

    private ChatResponse successResponse(String content, int promptTokens, int completionTokens, int totalTokens) {
        Generation generation = new Generation(new AssistantMessage(content));
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .withUsage(new MessageAggregator.DefaultUsage(promptTokens, completionTokens, totalTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }
}
