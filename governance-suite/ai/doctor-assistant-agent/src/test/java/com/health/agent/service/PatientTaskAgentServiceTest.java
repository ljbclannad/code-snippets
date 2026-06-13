package com.health.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.health.agent.dto.patient.AgentTaskConstraints;
import com.health.agent.dto.patient.AgentTaskSource;
import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskResponse;
import com.health.agent.dto.patient.DoctorAssistantTaskStatus;
import com.health.agent.dto.patient.MemorySnapshot;
import com.health.agent.dto.patient.TaskType;

class PatientTaskAgentServiceTest {

    private final PatientTaskAgentService service = new PatientTaskAgentService(new PatientTaskSafetyService());

    @Test
    void shouldBuildFollowUpAnalysisWithContextSnapshots() {
        DoctorAssistantTaskResponse response = service.analyze(baseRequest(TaskType.PATIENT_FOLLOW_UP_ANALYSIS));

        assertEquals(DoctorAssistantTaskStatus.SUCCESS, response.getStatus());
        assertEquals(TaskType.PATIENT_FOLLOW_UP_ANALYSIS, response.getTaskType());
        assertTrue(response.getPatientSafeReply().contains("整理了这次随访"));
        assertNotNull(response.getStructuredResult());
        assertEquals(2, response.getStructuredResult().getSupportingEvidence().size());
        assertNotNull(response.getDoctorWorkbenchPayload());
    }

    @Test
    void shouldEscalateHighRiskPatientMessage() {
        DoctorAssistantTaskRequest request = baseRequest(TaskType.RISK_STRATIFICATION_AND_ESCALATION);
        request.setPatientMessage("我现在胸痛还呼吸困难，是不是还能继续观察");

        DoctorAssistantTaskResponse response = service.analyze(request);

        assertEquals(DoctorAssistantTaskStatus.PENDING_APPROVAL, response.getStatus());
        assertTrue(response.getNeedsHumanReview());
        assertTrue(response.getPatientSafeReply().contains("尽快联系医生"));
        assertNotNull(response.getPendingTask());
        assertEquals("PATIENT_DIALOG_AGENT_TASK", response.getDoctorWorkbenchPayload().getAuditTag());
    }

    private DoctorAssistantTaskRequest baseRequest(TaskType taskType) {
        return DoctorAssistantTaskRequest.builder()
                .taskType(taskType)
                .goal("命中任务")
                .customerId("C001")
                .doctorId("D001")
                .source(AgentTaskSource.PATIENT_DIALOG_AI_CHAT)
                .patientMessage("请帮我分析最近一周的情况")
                .conversationId("conv-1")
                .correlationId("corr-1")
                .sessionContext(Map.of(
                        "conversationPhase", "follow_up",
                        "currentTopic", "血糖复盘"))
                .profileSnapshot(Map.of(
                        "age", 52,
                        "gender", "female"))
                .memorySnapshot(MemorySnapshot.builder()
                        .shortTermMemories(List.of("近3天血糖偏高"))
                        .longTermMemories(List.of("既往存在糖代谢异常"))
                        .build())
                .triggerDecision(Map.of("summary", "命中升级"))
                .constraints(AgentTaskConstraints.builder()
                        .patientVisibleResponseOnly(Boolean.TRUE)
                        .requireEvidence(Boolean.TRUE)
                        .build())
                .build();
    }
}
