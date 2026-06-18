package com.health.agent.engine;

import com.health.agent.client.DeepSeekClient;
import com.health.agent.model.Message;
import com.lx.governance.agentplatform.runtime.PlatformMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Flux;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class DeepSeekClientTest {

    @Mock
    private ChatModel chatModel;

    private DeepSeekClient deepSeekClient;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        deepSeekClient = new DeepSeekClient(chatModel);
        meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(deepSeekClient, "temperature", 0.7d);
        ReflectionTestUtils.setField(deepSeekClient, "maxTokens", 2000);
        ReflectionTestUtils.setField(deepSeekClient, "requestTimeout", Duration.ofMillis(100));
        ReflectionTestUtils.setField(deepSeekClient, "maxRetries", 2);
        ReflectionTestUtils.setField(deepSeekClient, "promptPricePer1kTokens", 0.5D);
        ReflectionTestUtils.setField(deepSeekClient, "completionPricePer1kTokens", 1.0D);
        ReflectionTestUtils.setField(deepSeekClient, "platformMetricsService", new PlatformMetricsService(meterRegistry));
    }

    @Test
    void shouldRetryTransientFailureAndEventuallyReturnResponse() {
        when(chatModel.call(any(Prompt.class)))
            .thenThrow(new RuntimeException("temporary"))
            .thenReturn(successResponse("ok"));

        DeepSeekClient.DeepSeekResponse response = deepSeekClient.chat(
            "system",
            List.of(Message.user("hello"))
        );

        assertEquals("ok", response.getContent());
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void shouldFailFastWhenModelCallExceedsTimeout() {
        doAnswer(invocation -> {
            Thread.sleep(200);
            return successResponse("late");
        }).when(chatModel).call(any(Prompt.class));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> deepSeekClient.chat(
            "system",
            List.of(Message.user("hello"))
        ));

        assertTrue(exception.getMessage().contains("超时"));
    }

    @Test
    void shouldStreamDeltasAndAggregateFullResponse() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
            successResponse("我需要"),
            successResponse("先查看"),
            successResponse("患者体重记录")
        ));

        List<String> deltas = new ArrayList<>();
        DeepSeekClient.DeepSeekResponse response = deepSeekClient.chatStream(
            "system",
            List.of(Message.user("hello")),
            deltas::add
        );

        assertEquals(List.of("我需要", "先查看", "患者体重记录"), deltas);
        assertEquals("我需要先查看患者体重记录", response.getContent());
        verify(chatModel).stream(any(Prompt.class));
    }

    @Test
    void shouldRecordAgentWorkflowTokenCostToPlatformMetrics() {
        when(chatModel.call(any(Prompt.class))).thenReturn(successResponse("ok"));

        deepSeekClient.chat("system", List.of(Message.user("hello")));

        assertEquals(30.0, meterRegistry.get("platform.tokens.total")
            .tag("scope", "AGENT_WORKFLOW")
            .summary().totalAmount());
        assertEquals(0.025D, meterRegistry.get("platform.cost.estimated")
            .tag("scope", "AGENT_WORKFLOW")
            .summary().totalAmount(), 0.0001D);
    }

    private ChatResponse successResponse(String content) {
        Generation generation = new Generation(new AssistantMessage(content));
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .withUsage(new MessageAggregator.DefaultUsage(10, 20, 30))
            .build();
        return new ChatResponse(List.of(generation), metadata);
    }
}
