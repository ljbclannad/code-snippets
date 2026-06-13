package com.health.agent.dto.patient;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorAssistantPendingTaskPayload {
    private Long pendingTaskId;
    private String agentSessionId;
    private String toolName;
    private String toolDescription;
    private String reason;
    private String status;
    private String reviewComment;
    private Long expireAt;
}
