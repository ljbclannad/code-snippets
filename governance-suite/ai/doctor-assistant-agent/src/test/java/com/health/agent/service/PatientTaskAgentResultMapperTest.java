package com.health.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.dto.patient.AgentTaskSource;
import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskStatus;
import com.health.agent.dto.patient.StructuredTaskResult;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.model.AgentResult;
import com.health.agent.model.PendingTask;
import com.health.agent.model.StructuredFinalAnswer;

class PatientTaskAgentResultMapperTest {

    private final PatientTaskAgentResultMapper mapper = new PatientTaskAgentResultMapper();

    @Test
    void shouldMapStructuredFinalAnswerToStructuredTaskResult() {
        StructuredTaskResult fallback = StructuredTaskResult.builder()
                .summary("fallback")
                .riskLevel("LOW")
                .needsAttention(false)
                .keyFindings(List.of("fallback finding"))
                .build();

        StructuredTaskResult result = mapper.toStructuredTaskResult(
                StructuredFinalAnswer.builder()
                        .summary("agent summary")
                        .riskLevel("MEDIUM")
                        .needsAttention(true)
                        .keyFindings(List.of("finding-1"))
                        .recommendedActions(List.of("action-1"))
                        .supportingEvidence(List.of("evidence-1"))
                        .evidenceSources(List.of("source-1"))
                        .uncertainties(List.of("uncertainty-1"))
                        .inferences(List.of("inference-1"))
                        .build(),
                fallback);

        assertEquals("agent summary", result.getSummary());
        assertEquals("MEDIUM", result.getRiskLevel());
        assertEquals(List.of("finding-1"), result.getKeyFindings());
        assertEquals(List.of("action-1"), result.getRecommendedActions());
    }

    @Test
    void shouldMapPendingTaskAndWorkbenchPayload() {
        PendingTask pendingTask = PendingTask.builder()
                .id(55L)
                .agentSessionId("session-1")
                .toolName("create_follow_up_task")
                .toolDescription("创建随访任务")
                .reason("需要医生确认")
                .status("PENDING")
                .reviewComment("待审批")
                .expireAt(LocalDateTime.now().plusHours(1))
                .build();

        var pendingTaskPayload = mapper.toPendingTaskPayload(pendingTask, "task-1");
        assertNotNull(pendingTaskPayload);
        assertEquals(55L, pendingTaskPayload.getPendingTaskId());

        StructuredTaskResult structuredResult = StructuredTaskResult.builder()
                .summary("summary")
                .riskLevel("HIGH")
                .needsAttention(true)
                .supportingEvidence(List.of("e1", "e2"))
                .keyFindings(List.of("f1"))
                .recommendedActions(List.of("a1"))
                .evidenceSources(List.of("s1"))
                .uncertainties(List.of("u1"))
                .build();

        DoctorAssistantTaskRequest request = DoctorAssistantTaskRequest.builder()
                .conversationId("conv-1")
                .correlationId("corr-1")
                .build();
        AgentExecutionCommand command = AgentExecutionCommand.builder()
                .source(AgentTaskSource.PATIENT_DIALOG_AI_CHAT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .build();

        var payload = mapper.toDoctorWorkbenchPayload(
                request,
                command,
                "task-1",
                "session-1",
                structuredResult,
                true,
                pendingTaskPayload,
                "AGENT",
                "PENDING_APPROVAL",
                null);
        assertEquals("PENDING_REVIEW", payload.getReviewStatus());
        assertEquals("HIGH", payload.getRiskLevel());
        assertEquals("PATIENT_DIALOG_AI_CHAT", payload.getSource());
    }

    @Test
    void shouldMapAgentStatusToPatientTaskStatus() {
        AgentResult success = AgentResult.success("s1", StructuredFinalAnswer.builder().summary("ok").build(), List.of(), 1);
        AgentResult pending = AgentResult.pendingApproval("s2", PendingTask.builder().build(), List.of(), 1);
        AgentResult error = AgentResult.error("s3", "err", List.of(), 1);

        assertEquals(DoctorAssistantTaskStatus.SUCCESS, mapper.toTaskStatus(success, false));
        assertEquals(DoctorAssistantTaskStatus.PENDING_APPROVAL, mapper.toTaskStatus(pending, true));
        assertEquals(DoctorAssistantTaskStatus.ERROR, mapper.toTaskStatus(error, false));
        assertEquals(DoctorAssistantTaskStatus.ERROR, mapper.toTaskStatus(null, false));
        assertNull(mapper.toPendingTaskPayload(null, "task-x"));
    }
}
