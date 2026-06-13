package com.health.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskResponse;
import com.health.agent.service.PatientTaskAgentService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/patient-tasks")
@RequiredArgsConstructor
public class PatientTaskController {

    private final PatientTaskAgentService patientTaskAgentService;

    @PostMapping("/analyze")
    public ResponseEntity<DoctorAssistantTaskResponse> analyze(@Valid @RequestBody DoctorAssistantTaskRequest request) {
        log.info("收到患者来源任务分析请求 - taskType: {}, conversationId: {}, correlationId: {}",
                request.getTaskType(), request.getConversationId(), request.getCorrelationId());
        return ResponseEntity.ok(patientTaskAgentService.analyze(request));
    }
}
