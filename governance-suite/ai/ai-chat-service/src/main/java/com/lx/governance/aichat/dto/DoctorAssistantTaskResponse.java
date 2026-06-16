package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * doctor-assistant-agent -> ai-chat-service 的响应 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorAssistantTaskResponse {

    /**
     * Agent 会话 ID。
     */
    private String agentSessionId;

    /**
     * 本次患者来源任务 ID。
     */
    private String taskId;

    /**
     * 回传主会话 ID，便于链路追踪。
     */
    private String conversationId;

    /**
     * 回传链路追踪 ID。
     */
    private String correlationId;

    /**
     * 本次任务状态。
     */
    private DoctorAssistantTaskStatus status;

    /**
     * 回传的任务类型。
     */
    private TaskType taskType;

    /**
     * 面向患者的安全回复。
     */
    private String patientSafeReply;

    /**
     * 面向医生的结构化分析结果。
     */
    private StructuredTaskResult structuredResult;

    /**
     * 面向工作台和治理系统的内部载荷。
     */
    private DoctorWorkbenchPayload doctorWorkbenchPayload;

    /**
     * 执行路径：真实 Agent 成功或模板回退。
     */
    private String executionPath;

    /**
     * 真实 Agent 原始执行状态。
     */
    private String agentExecutionStatus;

    /**
     * 发生模板回退时的原因。
     */
    private String fallbackReason;

    /**
     * 是否需要人工复核。
     */
    private Boolean needsHumanReview;

    /**
     * 待审批任务摘要。
     */
    private DoctorAssistantPendingTaskPayload pendingTask;

    /**
     * 调用失败或降级时的错误信息。
     */
    private String errorMessage;
}
