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

    /**
     * 待处理任务主键。
     */
    private Long pendingTaskId;
    /**
     * 关联的 Agent 会话 ID。
     */
    private String agentSessionId;
    /**
     * 待审批动作对应的工具名。
     */
    private String toolName;
    /**
     * 工具或动作的简要说明。
     */
    private String toolDescription;
    /**
     * 进入待审批状态的原因。
     */
    private String reason;
    /**
     * 当前审批状态。
     */
    private String status;
    /**
     * 医生或审核人填写的评语。
     */
    private String reviewComment;
    /**
     * 过期时间，毫秒时间戳。
     */
    private Long expireAt;
}
