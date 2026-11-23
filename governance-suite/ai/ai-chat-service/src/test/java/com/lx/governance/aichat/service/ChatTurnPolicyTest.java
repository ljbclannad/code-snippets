package com.lx.governance.aichat.service;

import com.lx.governance.aichat.config.ProfileCompletionProperties;
import com.lx.governance.aichat.dto.*;
import com.lx.governance.agentplatform.runtime.PlatformRouteDecision;
import com.lx.governance.agentplatform.runtime.PlatformRouteTarget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatTurnPolicyTest {
    private final MedicalKnowledgeQaService knowledge = mock(MedicalKnowledgeQaService.class);
    private final AgentConversationRouteRuntime runtime = mock(AgentConversationRouteRuntime.class);
    private final ProfileCompletionProperties completion = new ProfileCompletionProperties();
    private final ProfileDiagnosisService diagnosis = mock(ProfileDiagnosisService.class);
    private final FollowUpStateManager state = mock(FollowUpStateManager.class);
    private final FollowUpResponseBuilder builder = mock(FollowUpResponseBuilder.class);
    private final ChatTurnPolicy policy = new ChatTurnPolicy(null, knowledge, runtime, completion, diagnosis,
            state, builder, null, null);
    private final ChatRequest request = new ChatRequest();
    private final SessionContext context = SessionContext.builder().conversationId("c1").build();
    private final TaskTriggerDecision trigger = TaskTriggerDecision.builder().route(TaskTriggerRoute.DIRECT_CHAT).build();

    @Test void profileQuestionAndKnowledgeEscalationUseTheSamePrecedence() {
        completion.setEnabled(true);
        var missing = DiagnosisResult.builder().needsCompletion(true).missingFields(List.of(
                DiagnosisResult.MissingField.builder().fieldPath("age").build())).build();
        var profile = new UserProfileDTO();
        when(diagnosis.diagnoseProfile(profile, context, null)).thenReturn(missing);
        when(diagnosis.generateFollowUpQuestions(eq(missing), anyInt())).thenReturn(List.of("请补充年龄"));
        when(builder.buildFollowUpMessage(List.of("请补充年龄"), missing)).thenReturn("请补充年龄");
        assertEquals("请补充年龄", policy.profileQuestion(request, context, profile, trigger));
        verify(state).recordFollowUp("c1", List.of("请补充年龄"), List.of("age"));
        when(knowledge.shouldUseKnowledgeQa(trigger)).thenReturn(true);
        when(knowledge.answer(null, trigger)).thenReturn(MedicalKnowledgeQaService.QaResult.escalate("需要进一步复核"));
        assertNull(policy.profileQuestion(request, context, profile, trigger));
        when(runtime.evaluateInvocation(eq(request), eq(context), any())).thenReturn(
                new PlatformRouteDecision(PlatformRouteTarget.PENDING_REVIEW_SHORT_CIRCUIT, false, "pending"));
        var plan = policy.decide(request, context, trigger, false);
        assertEquals(ChatTurnPolicy.Route.PENDING, plan.route());
        assertEquals(TaskType.RISK_STRATIFICATION_AND_ESCALATION, plan.trigger().getTaskType());
        verify(runtime).evaluateInvocation(eq(request), eq(context), any());
    }

    @Test void blockedInvocationRespectsDisabledFallback() {
        trigger.setRoute(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT);
        when(runtime.evaluateInvocation(request, context, trigger)).thenReturn(
                new PlatformRouteDecision(PlatformRouteTarget.DIRECT_CHAT_FALLBACK, false, "blocked"));
        when(runtime.fallbackDecision("blocked")).thenReturn(
                new PlatformRouteDecision(PlatformRouteTarget.TERMINAL_ERROR, false, "blocked"));
        assertEquals(ChatTurnPolicy.Route.ERROR, policy.decide(request, context, trigger, false).route());
        assertEquals(ChatTurnPolicy.Route.DIRECT, policy.decide(request, context, trigger, true).route());
    }
}
