package com.health.agent.dto.patient;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 进入待审批或待人工接管链路时返回的任务摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorAssistantPendingTaskPayload {
    /**
     * 待处理任务主键。
     */
    private Long pendingTaskId;
    /**
     * 关联的 Agent 会话 ID。
     */
    private String agentSessionId;
    /**
     * 触发待审批的工具名。
     */
    private String toolName;
    /**
     * 工具动作描述。
     */
    private String toolDescription;
    /**
     * 进入待审批的原因。
     */
    private String reason;
    /**
     * 当前待处理状态。
     */
    private String status;
    /**
     * 审核意见。
     */
    private String reviewComment;
    /**
     * 任务过期时间，毫秒时间戳。
     */
    private Long expireAt;
}
