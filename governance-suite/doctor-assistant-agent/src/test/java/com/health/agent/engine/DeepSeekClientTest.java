package com.health.agent.engine;

import com.health.agent.client.DeepSeekClient;
import com.health.agent.model.Message;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeepSeekClientTest {

    @Mock
    private ChatModel chatModel;

    private DeepSeekClient deepSeekClient;

    @BeforeEach
    void setUp() {
        deepSeekClient = new DeepSeekClient(chatModel);
        ReflectionTestUtils.setField(deepSeekClient, "temperature", 0.7d);
        ReflectionTestUtils.setField(deepSeekClient, "maxTokens", 2000);
        ReflectionTestUtils.setField(deepSeekClient, "requestTimeout", Duration.ofMillis(100));
        ReflectionTestUtils.setField(deepSeekClient, "maxRetries", 2);
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

    private ChatResponse successResponse(String content) {
        Generation generation = new Generation(new AssistantMessage(content));
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .withUsage(new MessageAggregator.DefaultUsage(10, 20, 30))
            .build();
        return new ChatResponse(List.of(generation), metadata);
    }
}
