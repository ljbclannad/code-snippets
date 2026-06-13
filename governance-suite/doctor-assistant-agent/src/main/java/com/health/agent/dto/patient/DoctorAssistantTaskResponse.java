package com.health.agent.dto.patient;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorAssistantTaskResponse {
    private String agentSessionId;
    private String taskId;
    private String conversationId;
    private String correlationId;
    private DoctorAssistantTaskStatus status;
    private TaskType taskType;
    private String patientSafeReply;
    private StructuredTaskResult structuredResult;
    private DoctorWorkbenchPayload doctorWorkbenchPayload;
    private Boolean needsHumanReview;
    private DoctorAssistantPendingTaskPayload pendingTask;
    private String errorMessage;
}
