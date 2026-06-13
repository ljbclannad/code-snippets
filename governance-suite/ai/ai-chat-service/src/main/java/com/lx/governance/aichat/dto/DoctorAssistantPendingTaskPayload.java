package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * doctor-assistant-agent 返回的待审批任务摘要。
 */
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
