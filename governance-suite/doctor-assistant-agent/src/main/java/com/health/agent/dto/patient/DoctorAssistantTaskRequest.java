package com.health.agent.dto.patient;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorAssistantTaskRequest {
    private TaskType taskType;
    private String goal;
    private String customerId;
    private String doctorId;
    private AgentTaskSource source;
    private String patientMessage;
    private String conversationId;
    private String correlationId;
    private Map<String, Object> sessionContext;
    private Map<String, Object> profileSnapshot;
    private MemorySnapshot memorySnapshot;
    private Map<String, Object> triggerDecision;
    private AgentTaskConstraints constraints;
}
