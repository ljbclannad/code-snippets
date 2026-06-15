package com.health.agent.dto.patient;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorAssistantTaskRequest {
    @NotNull(message = "taskType 不能为空")
    private TaskType taskType;

    @Size(max = 500, message = "goal 长度不能超过 500")
    private String goal;

    @NotBlank(message = "customerId 不能为空")
    private String customerId;

    @NotBlank(message = "doctorId 不能为空")
    private String doctorId;

    private AgentTaskSource source;

    @NotBlank(message = "patientMessage 不能为空")
    @Size(max = 2000, message = "patientMessage 长度不能超过 2000")
    private String patientMessage;

    @NotBlank(message = "conversationId 不能为空")
    private String conversationId;

    @NotBlank(message = "correlationId 不能为空")
    private String correlationId;

    private Map<String, Object> sessionContext;
    private Map<String, Object> profileSnapshot;
    private MemorySnapshot memorySnapshot;
    private Map<String, Object> triggerDecision;
    private AgentTaskConstraints constraints;
}
