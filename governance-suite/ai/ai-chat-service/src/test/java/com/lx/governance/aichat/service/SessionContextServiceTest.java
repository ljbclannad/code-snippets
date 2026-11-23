package com.lx.governance.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.dto.DoctorAssistantTaskStatus;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerReason;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionContextServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private DeepSeekIntentAnalyzer intentAnalyzer;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private SessionContextService sessionContextService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void testGetOrCreateContext_NewContext() throws Exception {
        // Given
        String conversationId = "conv-001";
        String customerId = "user-001";
        String groupId = "group-001";
        String message = "我想咨询血糖问题";

        when(valueOperations.get(anyString())).thenReturn(null);

        SessionContext analyzedContext = SessionContext.builder()
            .currentTopic("血糖管理")
            .conversationPhase("consulting")
            .complexity("simple")
            .build();
        when(intentAnalyzer.analyzeIntentWithTimeout(anyString(), anyList(), anyLong()))
            .thenReturn(analyzedContext);

        // When
        SessionContext result = sessionContextService.getOrCreateContext(
            conversationId, customerId, groupId, message);

        // Then
        assertNotNull(result);
        assertEquals(conversationId, result.getConversationId());
        assertEquals(customerId, result.getCustomerId());
        assertEquals("血糖管理", result.getCurrentTopic());

        verify(valueOperations).get("ai:session:owned-context:" + conversationId);
        verify(valueOperations).get("ai:session:context:" + conversationId);
        verify(intentAnalyzer, times(1)).analyzeIntentWithTimeout(anyString(), anyList(), anyLong());
        verify(valueOperations, times(1)).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testGetOrCreateContext_ExistingContext() throws Exception {
        // Given
        String conversationId = "conv-001";
        SessionContext existingContext = SessionContext.builder()
            .conversationId(conversationId)
            .customerId("user-001")
            .currentTopic("血糖管理")
            .messageCount(2)
            .lastUpdateTime(System.currentTimeMillis())
            .build();

        String json = objectMapper.writeValueAsString(existingContext);
        when(valueOperations.get(anyString())).thenReturn(json);

        // When
        SessionContext result = sessionContextService.getOrCreateContext(
            conversationId, "user-001", "group-001", "继续咨询");

        // Then
        assertNotNull(result);
        assertEquals(conversationId, result.getConversationId());
        assertEquals(2, result.getMessageCount());

        verify(valueOperations, times(1)).get(anyString());
        verify(intentAnalyzer, never()).analyzeIntentWithTimeout(anyString(), anyList(), anyLong());
    }

    @Test
    void testUpdateContext_Success() throws Exception {
        // Given
        String conversationId = "conv-001";
        SessionContext existingContext = SessionContext.builder()
            .conversationId(conversationId)
            .customerId("user-001")
            .messageCount(1)
            .complexity("simple")
            .recentSummaries(new ArrayList<>())
            .build();

        String json = objectMapper.writeValueAsString(existingContext);
        when(valueOperations.get(anyString())).thenReturn(json);

        SessionContext updatedAnalysis = SessionContext.builder()
            .currentTopic("饮食建议")
            .conversationPhase("consulting")
            .complexity("simple")
            .build();
        when(intentAnalyzer.analyzeIntent(anyString(), anyList()))
            .thenReturn(updatedAnalysis);

        // When
        sessionContextService.updateContext(conversationId, "我应该吃什么", "建议...");

        // Then
        verify(valueOperations, times(1)).get(anyString());
        verify(intentAnalyzer, times(1)).analyzeIntent(anyString(), anyList());
        verify(valueOperations, times(1)).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testRecordTaskTriggerDecision_Success() throws Exception {
        String conversationId = "conv-001";
        SessionContext existingContext = SessionContext.builder()
            .conversationId(conversationId)
            .customerId("user-001")
            .messageCount(2)
            .recentSummaries(new ArrayList<>())
            .build();

        String json = objectMapper.writeValueAsString(existingContext);
        when(valueOperations.get(anyString())).thenReturn(json);

        TaskTriggerDecision decision = TaskTriggerDecision.builder()
            .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
            .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
            .reasons(java.util.List.of(
                TaskTriggerReason.EXPLICIT_ANALYSIS_REQUEST,
                TaskTriggerReason.TREND_ANALYSIS_REQUEST))
            .summary("命中随访趋势分析信号")
            .build();

        sessionContextService.recordTaskTriggerDecision(conversationId, decision);

        verify(valueOperations, times(1)).get(anyString());
        verify(valueOperations, times(1)).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testRecordAgentOutcome_SuccessWithWriteProtectionMetadata() throws Exception {
        String conversationId = "conv-001";
        SessionContext existingContext = SessionContext.builder()
            .conversationId(conversationId)
            .customerId("user-001")
            .contextVersion(2L)
            .lastMessageAt(1_000L)
            .recentSummaries(new ArrayList<>())
            .build();

        String json = objectMapper.writeValueAsString(existingContext);
        when(valueOperations.get(anyString())).thenReturn(json);

        DoctorAssistantTaskResponse response = DoctorAssistantTaskResponse.builder()
            .status(DoctorAssistantTaskStatus.SUCCESS)
            .executionPath("agent_success")
            .build();

        sessionContextService.recordAgentOutcome(conversationId, response, "req-002", 2_000L);

        verify(valueOperations, times(1)).get(anyString());
        verify(valueOperations, times(1)).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testRecordAgentOutcome_ShouldSkipStaleWrite() throws Exception {
        String conversationId = "conv-001";
        SessionContext existingContext = SessionContext.builder()
            .conversationId(conversationId)
            .customerId("user-001")
            .contextVersion(3L)
            .lastMessageAt(5_000L)
            .lastProcessedRequestId("req-new")
            .recentSummaries(new ArrayList<>())
            .build();

        String json = objectMapper.writeValueAsString(existingContext);
        when(valueOperations.get(anyString())).thenReturn(json);

        DoctorAssistantTaskResponse response = DoctorAssistantTaskResponse.builder()
            .status(DoctorAssistantTaskStatus.SUCCESS)
            .executionPath("agent_success")
            .build();

        sessionContextService.recordAgentOutcome(conversationId, response, "req-old", 4_000L);

        verify(valueOperations, times(1)).get(anyString());
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }
}
