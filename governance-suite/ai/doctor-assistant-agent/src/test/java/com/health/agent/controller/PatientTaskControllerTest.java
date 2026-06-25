package com.health.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.health.agent.dto.patient.AgentTaskConstraints;
import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskResponse;
import com.health.agent.dto.patient.DoctorAssistantTaskStatus;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.exception.AgentException;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.service.PatientTaskAgentService;

import java.util.concurrent.ExecutorService;

class PatientTaskControllerTest {

    @Test
    void shouldDelegatePatientTaskAnalysis() {
        PatientTaskAgentService service = mock(PatientTaskAgentService.class);
        ExecutorService streamExecutor = mock(ExecutorService.class);
        AgentAccessGuard accessGuard = mock(AgentAccessGuard.class);
        AgentMetrics agentMetrics = mock(AgentMetrics.class);
        PatientTaskController controller = new PatientTaskController(service, streamExecutor, accessGuard, agentMetrics);
        when(accessGuard.acquirePatientTask("C001")).thenReturn(mock(AgentAccessGuard.AccessPermit.class));

        DoctorAssistantTaskRequest request = DoctorAssistantTaskRequest.builder()
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .customerId("C001")
                .doctorId("D001")
                .patientMessage("test")
                .conversationId("conv-1")
                .correlationId("corr-1")
                .build();
        DoctorAssistantTaskResponse response = DoctorAssistantTaskResponse.builder()
                .status(DoctorAssistantTaskStatus.SUCCESS)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .patientSafeReply("safe reply")
                .build();
        when(service.analyze(request)).thenReturn(response);

        ResponseEntity<DoctorAssistantTaskResponse> actual = controller.analyze(request);

        assertEquals(HttpStatus.OK, actual.getStatusCode());
        assertEquals(response, actual.getBody());
        verify(service).analyze(request);
    }

    @Test
    void shouldReturnSkippedFallbackWhenPatientTaskEntryIsRejectedAndFallbackAllowed() {
        PatientTaskAgentService service = mock(PatientTaskAgentService.class);
        ExecutorService streamExecutor = mock(ExecutorService.class);
        AgentAccessGuard accessGuard = mock(AgentAccessGuard.class);
        AgentMetrics agentMetrics = mock(AgentMetrics.class);
        PatientTaskController controller = new PatientTaskController(service, streamExecutor, accessGuard, agentMetrics);

        DoctorAssistantTaskRequest request = DoctorAssistantTaskRequest.builder()
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .customerId("C001")
                .doctorId("D001")
                .patientMessage("test")
                .conversationId("conv-1")
                .correlationId("corr-1")
                .constraints(AgentTaskConstraints.builder()
                        .fallbackToDirectChatOnFailure(Boolean.TRUE)
                        .build())
                .build();
        DoctorAssistantTaskResponse fallbackResponse = DoctorAssistantTaskResponse.builder()
                .status(DoctorAssistantTaskStatus.SKIPPED)
                .fallbackReason("PATIENT_TASK_ENTRY_REJECTED")
                .build();
        when(accessGuard.acquirePatientTask("C001"))
                .thenThrow(new AgentException("PATIENT_TASK_OVERLOADED: 当前患者任务分析负载较高，请稍后再试"));
        when(service.buildEntryRejectedResponse(
                request,
                "PATIENT_TASK_ENTRY_REJECTED",
                "PATIENT_TASK_OVERLOADED: 当前患者任务分析负载较高，请稍后再试"))
                .thenReturn(fallbackResponse);

        ResponseEntity<DoctorAssistantTaskResponse> actual = controller.analyze(request);

        assertEquals(HttpStatus.OK, actual.getStatusCode());
        assertEquals(DoctorAssistantTaskStatus.SKIPPED, actual.getBody().getStatus());
        assertEquals("PATIENT_TASK_ENTRY_REJECTED", actual.getBody().getFallbackReason());
        verify(service).buildEntryRejectedResponse(
                request,
                "PATIENT_TASK_ENTRY_REJECTED",
                "PATIENT_TASK_OVERLOADED: 当前患者任务分析负载较高，请稍后再试");
    }
}
