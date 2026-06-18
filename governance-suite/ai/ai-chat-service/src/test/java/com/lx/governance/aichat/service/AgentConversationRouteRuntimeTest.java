package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.dto.DoctorAssistantTaskStatus;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;
import com.lx.governance.agentplatform.runtime.PlatformDispatchPolicyEngine;
import com.lx.governance.agentplatform.runtime.PlatformRouteDecision;
import com.lx.governance.agentplatform.runtime.PlatformRouteTarget;

class AgentConversationRouteRuntimeTest {

    @Test
    void shouldShortCircuitWhenPendingApprovalIsStillFresh() {
        AgentConversationRouteRuntime runtime = runtime(true);
        SessionContext sessionContext = SessionContext.builder()
                .lastAgentReviewStatus("PENDING_APPROVAL")
                .lastAgentOutcomeAt(System.currentTimeMillis() - 60_000)
                .build();

        PlatformRouteDecision decision = runtime.evaluateInvocation(
                new ChatRequest(),
                sessionContext,
                triggerDecision());

        assertEquals(PlatformRouteTarget.PENDING_REVIEW_SHORT_CIRCUIT, decision.target());
        assertFalse(decision.invokeAgent());
    }

    @Test
    void shouldFallbackToDirectChatWhenGrayPolicyBlocksInvocation() {
        AgentConversationRouteRuntime runtime = new AgentConversationRouteRuntime(
                new AgentDispatchPolicy(new PlatformDispatchPolicyEngine(
                        List.of("PATIENT_FOLLOW_UP_ANALYSIS"), List.of("group-2"), List.of(), List.of(), List.of(), false)),
                true,
                10 * 60 * 1000L);
        ChatRequest request = new ChatRequest();
        request.setGroupId("group-1");

        PlatformRouteDecision decision = runtime.evaluateInvocation(
                request,
                SessionContext.builder().build(),
                triggerDecision());

        assertEquals(PlatformRouteTarget.DIRECT_CHAT_FALLBACK, decision.target());
        assertEquals("group_not_gray_enabled", decision.reason());
    }

    @Test
    void shouldConsumeAgentResponseWhenReplyIsAvailable() {
        AgentConversationRouteRuntime runtime = runtime(true);
        DoctorAssistantTaskResponse response = DoctorAssistantTaskResponse.builder()
                .status(DoctorAssistantTaskStatus.SUCCESS)
                .patientSafeReply("可以继续观察")
                .build();

        PlatformRouteDecision decision = runtime.evaluateAgentResponse(response);

        assertEquals(PlatformRouteTarget.AGENT, decision.target());
        assertTrue(decision.invokeAgent());
    }

    @Test
    void shouldFallbackWhenAgentReturnsSkipped() {
        AgentConversationRouteRuntime runtime = runtime(true);
        DoctorAssistantTaskResponse response = DoctorAssistantTaskResponse.builder()
                .status(DoctorAssistantTaskStatus.SKIPPED)
                .build();

        PlatformRouteDecision decision = runtime.evaluateAgentResponse(response);

        assertEquals(PlatformRouteTarget.DIRECT_CHAT_FALLBACK, decision.target());
        assertEquals("agent_skipped", decision.reason());
    }

    @Test
    void shouldReturnTerminalErrorWhenAutoFallbackDisabled() {
        AgentConversationRouteRuntime runtime = runtime(false);

        PlatformRouteDecision decision = runtime.fallbackDecision("agent_exception");

        assertEquals(PlatformRouteTarget.TERMINAL_ERROR, decision.target());
        assertFalse(decision.invokeAgent());
    }

    private AgentConversationRouteRuntime runtime(boolean autoFallbackToDirectChat) {
        return new AgentConversationRouteRuntime(
                new AgentDispatchPolicy(new PlatformDispatchPolicyEngine(
                        List.of("PATIENT_FOLLOW_UP_ANALYSIS"), List.of("group-1"), List.of(), List.of(), List.of(), false)),
                autoFallbackToDirectChat,
                10 * 60 * 1000L);
    }

    private TaskTriggerDecision triggerDecision() {
        return TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .build();
    }
}
