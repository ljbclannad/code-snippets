package com.lx.governance.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.ChatGenerationResult;
import com.lx.governance.aichat.dto.ChatHistorySyncMessage;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.entity.AiChatHistory;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatAsyncProcessingServiceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void structuredUsageSurvivesSerializationAndWinsOverLegacyText(boolean rejectedExecutor) throws Exception {
        var history = mock(AiChatHistoryMapper.class);
        var service = new ChatAsyncProcessingService(history, mock(RabbitTemplate.class), mock(MemoryService.class),
                task -> {
                    if (rejectedExecutor) {
                        throw new RejectedExecutionException("模拟线程池满");
                    }
                    task.run();
                }, mock(VisitorIdentityService.class));
        var result = new ChatGenerationResult();
        result.setConversationId("conversation");
        result.setAnswer("测试回复");
        var metadata = new ChatGenerationResult.Metadata();
        metadata.recordTokenUsage(ChatGenerationResult.TokenUsage.fromCounts(176, 0, 176));
        metadata.setUsage("prompt_tokens=999, completion_tokens=999, total_tokens=1998");
        result.setMetadata(metadata);
        var json = new ObjectMapper();
        result = json.readValue(json.writeValueAsString(result), ChatGenerationResult.class);
        service.handleChatCompleted(new ChatRequest(), result, "trace");
        var saved = ArgumentCaptor.forClass(AiChatHistory.class);
        verify(history).insert(saved.capture());
        assertEquals(176, saved.getValue().getPromptTokens());
        assertEquals(0, saved.getValue().getCompletionTokens());
        assertEquals(176, saved.getValue().getTotalTokens());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "prompt_tokens=176, completion_tokens=108, total_tokens=284|176|108|284",
            "prompt_tokens=12, completion_tokens=0, total_tokens=12|12|0|12",
            "prompt_tokens=7|7|null|null",
            "prompt_tokens=-1, completion_tokens=overflow, total_tokens=999999999999|null|null|null",
            "malformed|null|null|null",
            "null|null|null|null"
    }, delimiter = '|', nullValues = "null")
    void retainsKnownUsageInHistoryReplayAndSearchSync(String usage, Integer prompt, Integer completion, Integer total) throws Exception {
        var mapper = mock(AiChatHistoryMapper.class);
        var rabbit = mock(RabbitTemplate.class);
        var service = new ChatAsyncProcessingService(mapper, rabbit, mock(MemoryService.class), Runnable::run,
                mock(VisitorIdentityService.class));
        ReflectionTestUtils.setField(service, "syncEnabled", true);
        var request = new ChatRequest();
        request.setCustomerId("synthetic-patient");
        request.setMessage("什么是健康档案？");
        var response = new ChatGenerationResult();
        response.setConversationId("conversation");
        response.setAnswer("概念解释");
        if (usage != null) {
            var metadata = new ChatGenerationResult.Metadata();
            metadata.setUsage(usage);
            response.setMetadata(metadata);
        }
        var json = new ObjectMapper();
        response = json.readValue(json.writeValueAsString(response), ChatGenerationResult.class);
        service.handleChatCompleted(request, response, "trace");
        var row = ArgumentCaptor.forClass(AiChatHistory.class);
        verify(mapper).insert(row.capture());
        assertEquals("概念解释", row.getValue().getAiResponse());
        assertEquals(prompt, row.getValue().getPromptTokens());
        assertEquals(completion, row.getValue().getCompletionTokens());
        assertEquals(total, row.getValue().getTotalTokens());
        when(mapper.selectList(any())).thenReturn(List.of(row.getValue()));
        var replay = new ChatHistoryService(mapper).getConversationHistory("synthetic-patient", "conversation");
        assertEquals(total, replay.getMessages().get(1).getTotalTokens());
        var synced = ArgumentCaptor.forClass(ChatHistorySyncMessage.class);
        verify(rabbit).convertAndSend(anyString(), anyString(), synced.capture());
        assertEquals(prompt, synced.getValue().getPromptTokens());
        assertEquals(completion, synced.getValue().getCompletionTokens());
        assertEquals(total, synced.getValue().getTotalTokens());
    }
}
