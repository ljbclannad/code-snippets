package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;

class AgentDispatchPolicyTest {

    @Test
    void shouldAllowGrayEnabledTaskAndGroup() {
        AgentDispatchPolicy policy = new AgentDispatchPolicy(
                List.of("PATIENT_FOLLOW_UP_ANALYSIS"),
                List.of("group-1"),
                false);

        ChatRequest request = new ChatRequest();
        request.setGroupId("group-1");

        AgentDispatchPolicy.Decision decision = policy.evaluate(request, TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .build());

        assertTrue(decision.invokeAgent());
    }

    @Test
    void shouldBlockWhenGroupIsNotEnabled() {
        AgentDispatchPolicy policy = new AgentDispatchPolicy(
                List.of("PATIENT_FOLLOW_UP_ANALYSIS"),
                List.of("group-2"),
                false);

        ChatRequest request = new ChatRequest();
        request.setGroupId("group-1");

        AgentDispatchPolicy.Decision decision = policy.evaluate(request, TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .build());

        assertFalse(decision.invokeAgent());
    }
}
