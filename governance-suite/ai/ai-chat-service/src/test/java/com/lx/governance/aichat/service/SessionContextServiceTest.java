package com.lx.governance.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.dto.DoctorAssistantTaskStatus;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerContext;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
            .userIntent("general_health_consultation")
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

        sessionContextService.updateContext(conversationId, "继续咨询", "请继续描述您的情况。");

        verify(valueOperations, times(2)).get(anyString());
        verifyNoInteractions(intentAnalyzer);
    }

    @Test
    void firstTurnAnalyzesOnceAndCompletionKeepsAnalysisAndRoutingMetadata() throws Exception {
        String conversationId = "conv-001";
        String message = "我想咨询血糖问题";
        AtomicReference<String> stored = mockStoredContext(conversationId, null);
        SessionContext analyzedContext = SessionContext.builder()
                .currentTopic("血糖管理")
                .userIntent("general_health_consultation")
                .pendingQuestions(List.of("最近一次血糖是多少？"))
                .complexity("simple")
                .build();
        when(intentAnalyzer.analyzeIntentWithTimeout(eq(message), anyList(), anyLong())).thenReturn(analyzedContext);

        sessionContextService.getOrCreateContext(conversationId, "user-001", "group-001", message, "req-1", 1_000L);
        sessionContextService.recordTaskTriggerDecision(conversationId, TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DIRECT_CHAT)
                .taskType(TaskType.NONE)
                .reasons(List.of(TaskTriggerReason.PROFILE_COMPLETION_FOLLOW_UP))
                .build(), "req-1", 1_000L);
        sessionContextService.updateContext(conversationId, message, "请补充最近的测量值。", "req-1", 1_000L);

        SessionContext completed = objectMapper.readValue(stored.get(), SessionContext.class);
        assertEquals("血糖管理", completed.getCurrentTopic());
        assertEquals(List.of("最近一次血糖是多少？"), completed.getPendingQuestions());
        assertEquals(List.of(TaskTriggerReason.PROFILE_COMPLETION_FOLLOW_UP), completed.getLastTaskTriggerReasons());
        assertEquals(2, completed.getMessageCount());
        assertEquals(List.of(message), completed.getRecentSummaries().stream().map(MessageSummary::getUserMessage).toList());
        assertEquals("req-1", completed.getLastProcessedRequestId());
        verify(intentAnalyzer).analyzeIntentWithTimeout(eq(message), anyList(), anyLong());
        verifyNoMoreInteractions(intentAnalyzer);
    }

    @Test
    void shortFollowUpReanalyzesWithHistoryBeforeRoutingAndOnlyOnce() throws Exception {
        String conversationId = "conv-001";
        String message = "还是没怎么掉";
        MessageSummary previous = MessageSummary.builder().userMessage("过去一周都在记录体重").build();
        SessionContext existing = SessionContext.builder()
                .conversationId(conversationId)
                .customerId("user-001")
                .currentTopic("减肥")
                .userIntent("general_health_consultation")
                .complexity("simple")
                .lastUpdateTime(System.currentTimeMillis())
                .messageCount(3)
                .recentSummaries(List.of(previous))
                .build();
        AtomicReference<String> stored = mockStoredContext(conversationId, existing);
        when(intentAnalyzer.analyzeIntentWithTimeout(eq(message), eq(List.of(previous)), anyLong()))
                .thenReturn(SessionContext.builder().currentTopic("减肥").userIntent("follow_up_trend_analysis").build());

        SessionContext context = sessionContextService.getOrCreateContext(
                conversationId, "user-001", "group-001", message, "req-2", 2_000L);
        TaskTriggerDecision decision = new TaskTriggerRouter().route(TaskTriggerContext.builder()
                .message(message).sessionContext(context).build());
        sessionContextService.updateContext(conversationId, message, "已提交分析。", "req-2", 2_000L);

        assertEquals(TaskType.PATIENT_FOLLOW_UP_ANALYSIS, decision.getTaskType());
        SessionContext completed = objectMapper.readValue(stored.get(), SessionContext.class);
        assertEquals("follow_up_trend_analysis", completed.getUserIntent());
        assertEquals(2, completed.getRecentSummaries().size());
        verify(intentAnalyzer).analyzeIntentWithTimeout(eq(message), eq(List.of(previous)), anyLong());
        verifyNoMoreInteractions(intentAnalyzer);
    }

    @Test
    void priorIdleIntentAndPendingQuestionsDoNotMaskNewSensitiveRequest() throws Exception {
        String conversationId = "conv-001";
        String message = "我的药能自行减半吗";
        SessionContext existing = SessionContext.builder()
                .conversationId(conversationId)
                .customerId("user-001")
                .currentTopic("用药咨询")
                .userIntent("casual_chat")
                .pendingQuestions(List.of("请补充睡眠情况"))
                .messageCount(3)
                .lastUpdateTime(System.currentTimeMillis())
                .build();
        mockStoredContext(conversationId, existing);
        when(intentAnalyzer.analyzeIntentWithTimeout(eq(message), anyList(), anyLong()))
                .thenReturn(SessionContext.builder().currentTopic("用药咨询").userIntent("risk_review_request").build());

        SessionContext context = sessionContextService.getOrCreateContext(conversationId, "user-001", "group-001", message);

        TaskTriggerDecision decision = new TaskTriggerRouter().route(TaskTriggerContext.builder()
                .message(message).sessionContext(context).build());
        assertEquals(TaskType.RISK_STRATIFICATION_AND_ESCALATION, decision.getTaskType());
        assertTrue(context.getPendingQuestions().isEmpty());
        verify(intentAnalyzer).analyzeIntentWithTimeout(eq(message), anyList(), anyLong());
        verifyNoMoreInteractions(intentAnalyzer);
    }

    private AtomicReference<String> mockStoredContext(String conversationId, SessionContext initial) throws Exception {
        String key = "ai:session:owned-context:" + conversationId;
        AtomicReference<String> stored = new AtomicReference<>(
                initial != null ? objectMapper.writeValueAsString(initial) : null);
        when(valueOperations.get(key)).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(eq(key), anyString(), anyLong(), any(TimeUnit.class));
        return stored;
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
