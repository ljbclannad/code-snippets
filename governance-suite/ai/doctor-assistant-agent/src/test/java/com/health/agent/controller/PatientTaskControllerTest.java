package com.health.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskResponse;
import com.health.agent.dto.patient.DoctorAssistantTaskStatus;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.service.PatientTaskAgentService;

class PatientTaskControllerTest {

    @Test
    void shouldDelegatePatientTaskAnalysis() {
        PatientTaskAgentService service = mock(PatientTaskAgentService.class);
        PatientTaskController controller = new PatientTaskController(service);

        DoctorAssistantTaskRequest request = DoctorAssistantTaskRequest.builder()
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
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
}
