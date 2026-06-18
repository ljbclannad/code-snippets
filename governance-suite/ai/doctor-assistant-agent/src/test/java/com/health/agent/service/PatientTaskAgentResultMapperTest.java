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
import com.health.agent.dto.patient.MemorySnapshot;
import com.health.agent.dto.patient.StructuredTaskResult;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.model.AgentResult;
import com.health.agent.model.PendingTask;
import com.health.agent.model.StructuredFinalAnswer;
import com.lx.governance.agentplatform.model.PlatformTraceContext;
import com.lx.governance.agentplatform.model.WorkbenchActionType;
import com.lx.governance.agentplatform.model.WorkbenchViewType;

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
                fallback,
                AgentExecutionCommand.builder()
                        .sessionContext(java.util.Map.of("phase", "follow_up"))
                        .profileSnapshot(java.util.Map.of("age", 30))
                        .memorySnapshot(MemorySnapshot.builder()
                                .shortTermMemories(List.of("近3天体重变化小"))
                                .build())
                        .triggerDecision(java.util.Map.of("summary", "命中升级"))
                        .build());

        assertEquals("agent summary", result.getSummary());
        assertEquals("MEDIUM", result.getRiskLevel());
        assertEquals(List.of("finding-1"), result.getKeyFindings());
        assertEquals(List.of("action-1"), result.getRecommendedActions());
        assertEquals(List.of("source-1", "AI_CHAT_SESSION_CONTEXT", "PROFILE_SNAPSHOT", "AI_CHAT_MEMORY", "TRIGGER_DECISION"),
                result.getEvidenceSources());
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
                .evidenceSources(List.of("AI_CHAT_SESSION_CONTEXT", "WEIGHT_HISTORY"))
                .inferences(List.of("需要人工确认"))
                .uncertainties(List.of("u1"))
                .build();

        DoctorAssistantTaskRequest request = DoctorAssistantTaskRequest.builder()
                .conversationId("conv-1")
                .correlationId("corr-1")
                .traceContext(PlatformTraceContext.builder()
                        .traceId("trace-1")
                        .conversationId("conv-1")
                        .correlationId("corr-1")
                        .build())
                .build();
        AgentExecutionCommand command = AgentExecutionCommand.builder()
                .source(AgentTaskSource.PATIENT_DIALOG_AI_CHAT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .sessionContext(java.util.Map.of("phase", "follow_up"))
                .profileSnapshot(java.util.Map.of("age", 30))
                .memorySnapshot(MemorySnapshot.builder().shortTermMemories(List.of("近3天体重偏高")).build())
                .triggerDecision(java.util.Map.of("summary", "命中升级"))
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
        assertEquals(List.of("WEIGHT_HISTORY"), payload.getAgentEvidenceSources());
        assertEquals(List.of("AI_CHAT_SESSION_CONTEXT"), payload.getUpstreamEvidenceSources());
        assertEquals(List.of("需要人工确认"), payload.getInferenceEvidence());

        var platformPayload = mapper.toPlatformWorkbenchPayload(
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
        assertEquals(WorkbenchViewType.APPROVAL, platformPayload.getViewType());
        assertEquals(4, platformPayload.getAvailableActions().size());
        assertEquals(WorkbenchActionType.APPROVE, platformPayload.getAvailableActions().get(0).getActionType());
        assertEquals(List.of("需要人工确认"), platformPayload.getViewData().get("inferenceEvidence"));
        assertEquals(List.of("WEIGHT_HISTORY"), platformPayload.getExtensions().get("agentEvidenceSources"));

        var traceContext = mapper.toTraceContext(request, command, "run-1", "agent-1", "create_follow_up_task");
        assertEquals("trace-1", traceContext.getTraceId());
        assertEquals("run-1", traceContext.getRunId());
        assertEquals("agent-1", traceContext.getAgentId());
        assertEquals("create_follow_up_task", traceContext.getToolName());
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
