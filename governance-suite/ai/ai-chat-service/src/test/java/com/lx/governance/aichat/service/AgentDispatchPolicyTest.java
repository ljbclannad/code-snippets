package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;
import com.lx.governance.agentplatform.runtime.PlatformDispatchPolicyEngine;

class AgentDispatchPolicyTest {

    @Test
    void shouldAllowGrayEnabledTaskAndGroup() {
        AgentDispatchPolicy policy = new AgentDispatchPolicy(new PlatformDispatchPolicyEngine(
                List.of("PATIENT_FOLLOW_UP_ANALYSIS"),
                List.of("group-1"),
                List.of(),
                List.of(),
                List.of(),
                false));

        ChatRequest request = new ChatRequest();
        request.setGroupId("group-1");
        request.setCustomerId("C001");

        AgentDispatchPolicy.Decision decision = policy.evaluate(request, SessionContext.builder().build(), TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .build());

        assertTrue(decision.invokeAgent());
    }

    @Test
    void shouldBlockWhenGroupIsNotEnabled() {
        AgentDispatchPolicy policy = new AgentDispatchPolicy(new PlatformDispatchPolicyEngine(
                List.of("PATIENT_FOLLOW_UP_ANALYSIS"),
                List.of("group-2"),
                List.of(),
                List.of(),
                List.of(),
                false));

        ChatRequest request = new ChatRequest();
        request.setGroupId("group-1");
        request.setCustomerId("C001");

        AgentDispatchPolicy.Decision decision = policy.evaluate(request, SessionContext.builder().build(), TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .build());

        assertFalse(decision.invokeAgent());
    }

    @Test
    void shouldBlockWhenTenantIsNotEnabled() {
        AgentDispatchPolicy policy = new AgentDispatchPolicy(new PlatformDispatchPolicyEngine(
                List.of("PATIENT_FOLLOW_UP_ANALYSIS"),
                List.of("group-1"),
                List.of(),
                List.of("tenant-a"),
                List.of(),
                false));

        ChatRequest request = new ChatRequest();
        request.setGroupId("group-1");
        request.setCustomerId("C001");
        request.setTenantId("tenant-b");

        AgentDispatchPolicy.Decision decision = policy.evaluate(request, SessionContext.builder().build(), TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .build());

        assertFalse(decision.invokeAgent());
    }
}
