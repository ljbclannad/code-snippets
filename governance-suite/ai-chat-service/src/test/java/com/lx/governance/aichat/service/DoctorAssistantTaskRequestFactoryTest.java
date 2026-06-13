package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.dto.AgentTaskSource;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.DoctorAssistantTaskRequest;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerReason;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;
import com.lx.governance.aichat.dto.UserProfileDTO;

class DoctorAssistantTaskRequestFactoryTest {

    private final DoctorAssistantTaskRequestFactory factory = new DoctorAssistantTaskRequestFactory();

    @Test
    void shouldPackageSessionContextIntoAgentRequest() {
        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-1");
        request.setCustomerId("customer-1");
        request.setMessage("请帮我分析最近一周血糖变化");

        SessionContext sessionContext = SessionContext.builder()
                .conversationId("conv-1")
                .customerId("customer-1")
                .groupId("group-1")
                .conversationPhase("follow_up")
                .userIntent("随访分析")
                .complexity("complex")
                .build();

        TaskTriggerDecision decision = TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .reasons(java.util.List.of(TaskTriggerReason.EXPLICIT_ANALYSIS_REQUEST))
                .summary("命中随访分析升级条件")
                .build();

        DoctorAssistantTaskRequest agentRequest = factory.buildPatientDialogRequest(
                request,
                sessionContext,
                new UserProfileDTO(),
                LayeredMemories.builder().build(),
                decision,
                "corr-1");

        assertEquals(TaskType.PATIENT_FOLLOW_UP_ANALYSIS, agentRequest.getTaskType());
        assertEquals("命中随访分析升级条件", agentRequest.getGoal());
        assertEquals("customer-1", agentRequest.getCustomerId());
        assertEquals("conv-1", agentRequest.getConversationId());
        assertEquals("corr-1", agentRequest.getCorrelationId());
        assertEquals(AgentTaskSource.PATIENT_DIALOG_AI_CHAT, agentRequest.getSource());
        assertSame(sessionContext, agentRequest.getSessionContext());
        assertSame(decision, agentRequest.getTriggerDecision());
        assertNotNull(agentRequest.getConstraints());
        assertTrue(agentRequest.getConstraints().getPatientVisibleResponseOnly());
        assertTrue(agentRequest.getConstraints().getFallbackToDirectChatOnFailure());
        assertEquals(8000, agentRequest.getConstraints().getTimeoutMillis());
        assertEquals("patient-dialog:conv-1:" + Integer.toHexString("请帮我分析最近一周血糖变化".hashCode()),
                agentRequest.getConstraints().getIdempotencyKey());
    }
}
