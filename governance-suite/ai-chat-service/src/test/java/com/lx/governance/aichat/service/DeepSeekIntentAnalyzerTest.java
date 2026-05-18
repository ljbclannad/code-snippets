package com.lx.governance.aichat.service;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeepSeekIntentAnalyzerTest {

    @Mock
    private DeepSeekClient deepSeekClient;

    @InjectMocks
    private DeepSeekIntentAnalyzer intentAnalyzer;

    @Test
    void testAnalyzeIntent_Success() {
        // Given
        String message = "我的血糖最近有点高";
        String mockResponse = "{\n" +
            "  \"currentTopic\": \"血糖管理\",\n" +
            "  \"conversationPhase\": \"consulting\",\n" +
            "  \"mentionedEntities\": [\"血糖\"],\n" +
            "  \"extractedData\": {},\n" +
            "  \"userIntent\": \"咨询血糖问题\",\n" +
            "  \"pendingQuestions\": [\"具体血糖值是多少？\"],\n" +
            "  \"complexity\": \"simple\"\n" +
            "}";

        DifyResponse difyResponse = new DifyResponse();
        difyResponse.setAnswer(mockResponse);
        when(deepSeekClient.chat(any(ChatRequest.class), isNull(), isNull())).thenReturn(difyResponse);

        // When
        SessionContext result = intentAnalyzer.analyzeIntent(message, Collections.emptyList());

        // Then
        assertNotNull(result);
        assertEquals("血糖管理", result.getCurrentTopic());
        assertEquals("consulting", result.getConversationPhase());
        assertEquals("simple", result.getComplexity());
        assertEquals(1, result.getMentionedEntities().size());
        assertEquals("血糖", result.getMentionedEntities().get(0));

        verify(deepSeekClient, times(1)).chat(any(ChatRequest.class), isNull(), isNull());
    }

    @Test
    void testAnalyzeIntent_WithHistory() {
        // Given
        String message = "那我应该怎么控制？";
        MessageSummary history = MessageSummary.builder()
            .userMessage("我的血糖最近有点高")
            .timestamp(System.currentTimeMillis())
            .build();

        String mockResponse = "{\n" +
            "  \"currentTopic\": \"血糖控制方法\",\n" +
            "  \"conversationPhase\": \"consulting\",\n" +
            "  \"mentionedEntities\": [\"血糖\", \"控制方法\"],\n" +
            "  \"extractedData\": {},\n" +
            "  \"userIntent\": \"询问血糖控制方法\",\n" +
            "  \"pendingQuestions\": [],\n" +
            "  \"complexity\": \"complex\"\n" +
            "}";

        DifyResponse difyResponse = new DifyResponse();
        difyResponse.setAnswer(mockResponse);
        when(deepSeekClient.chat(any(ChatRequest.class), isNull(), isNull())).thenReturn(difyResponse);

        // When
        SessionContext result = intentAnalyzer.analyzeIntent(message, Arrays.asList(history));

        // Then
        assertNotNull(result);
        assertEquals("血糖控制方法", result.getCurrentTopic());
        assertEquals("complex", result.getComplexity());

        verify(deepSeekClient, times(1)).chat(any(ChatRequest.class), isNull(), isNull());
    }

    @Test
    void testAnalyzeIntent_Fallback() {
        // Given
        String message = "测试消息";
        when(deepSeekClient.chat(any(ChatRequest.class), isNull(), isNull()))
            .thenThrow(new RuntimeException("API错误"));

        // When
        SessionContext result = intentAnalyzer.analyzeIntent(message, Collections.emptyList());

        // Then
        assertNotNull(result);
        assertEquals("健康咨询", result.getCurrentTopic());
        assertEquals("consulting", result.getConversationPhase());
        assertEquals("simple", result.getComplexity());
    }
}
