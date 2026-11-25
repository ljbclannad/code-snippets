package com.lx.governance.aichat.service;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.config.ProfileCompletionProperties;
import com.lx.governance.aichat.dto.*;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntentFallbackPolicyTest {
    @Mock private DeepSeekClient client;
    @Mock private AiChatMetrics metrics;
    @InjectMocks private DeepSeekIntentAnalyzer analyzer;
    private final ProfileDiagnosisService diagnosis = mock(ProfileDiagnosisService.class);
    private final FollowUpStateManager followUp = mock(FollowUpStateManager.class);
    private final ChatTurnPolicy policy = new ChatTurnPolicy(new TaskTriggerRouter(),
            mock(MedicalKnowledgeQaService.class), null, new ProfileCompletionProperties(), diagnosis,
            followUp, mock(FollowUpResponseBuilder.class), null, null);

    @Test
    void timedOutFactQuestionDoesNotBecomeProfileCollection() {
        var registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(analyzer, "metrics", new AiChatMetrics(registry));
        when(client.chatWithTimeout(any(), eq(5000L)))
                .thenThrow(new CompletionException(new TimeoutException("模拟意图超时")));
        var context = analyzer.analyzeIntentWithTimeout("什么是健康档案？", List.of(), 5000L);
        var request = request("什么是健康档案？");
        var decision = policy.trigger(request, context);
        assertEquals("unknown", context.getUserIntent());
        assertFalse(policy.loadProfile(false, context));
        assertNull(policy.profileQuestion(request, context, new UserProfileDTO(), decision));
        assertEquals(ChatTurnPolicy.Route.DIRECT, policy.decide(request, context, decision, false).route());
        verifyNoInteractions(diagnosis, followUp);
        assertEquals(1.0, registry.get("ai.intent.analysis.fallbacks").counter().count());
    }

    @Test
    void failedClassificationStillPreservesHighRiskRouting() {
        when(client.chatWithTimeout(any(), eq(5000L))).thenThrow(new RuntimeException("模拟服务失败"));
        var context = analyzer.analyzeIntentWithTimeout("胸痛，呼吸困难，需要医生复核", List.of(), 5000L);
        var decision = policy.trigger(request("胸痛，呼吸困难，需要医生复核"), context);
        assertTrue(decision.shouldUpgradeToAgent());
        assertEquals(TaskType.RISK_STRATIFICATION_AND_ESCALATION, decision.getTaskType());
    }

    @ParameterizedTest
    @ValueSource(strings = {"不是合法 JSON", "{}", "{\"userIntent\":\"unregistered\"}"})
    void invalidModelOutputDoesNotInventAConsultationIntent(String output) {
        var generated = new ChatGenerationResult();
        generated.setAnswer(output);
        when(client.chatWithTimeout(any(), eq(5000L))).thenReturn(generated);
        var context = analyzer.analyzeIntentWithTimeout("什么是健康档案？", List.of(), 5000L);
        assertEquals("unknown", context.getUserIntent());
        assertFalse(policy.loadProfile(false, context));
        verify(metrics).recordIntentAnalysisFallback();
    }

    @Test
    void failedProfileLoadDoesNotMeanPatientHasMissingFields() {
        var context = SessionContext.builder().userIntent("general_health_consultation").build();
        var decision = TaskTriggerDecision.builder().route(TaskTriggerRoute.DIRECT_CHAT).build();
        assertNull(policy.profileQuestion(request("一般健康问题"), context, null, decision));
        verifyNoInteractions(diagnosis, followUp);
    }

    private ChatRequest request(String message) {
        var request = new ChatRequest();
        request.setCustomerId("synthetic-patient");
        request.setConversationId("conversation");
        request.setMessage(message);
        return request;
    }
}
