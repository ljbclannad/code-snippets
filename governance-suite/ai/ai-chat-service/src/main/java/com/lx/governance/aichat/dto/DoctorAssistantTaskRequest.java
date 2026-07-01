package com.lx.governance.aichat.dto;

import com.lx.governance.agentplatform.model.PlatformContext;
import com.lx.governance.agentplatform.model.PlatformTraceContext;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ai-chat-service -> doctor-assistant-agent 的任务请求 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorAssistantTaskRequest {

    /**
     * 触发后的任务类型。
     */
    private TaskType taskType;

    /**
     * Agent 需要完成的任务目标。
     */
    private String goal;

    /**
     * 患者 ID。
     */
    private String customerId;

    /**
     * 责任医生或工作台医生 ID。
     */
    private String doctorId;

    /**
     * 调用来源。
     */
    private AgentTaskSource source;

    /**
     * 当前轮患者原始消息。
     */
    private String patientMessage;

    /**
     * 主对话会话 ID。
     */
    private String conversationId;

    /**
     * 上游链路追踪 ID。
     */
    private String correlationId;

    /**
     * 平台统一 trace 上下文。
     */
    private PlatformTraceContext traceContext;

    /**
     * 平台统一上下文总线载荷。
     */
    private PlatformContext platformContext;

    /**
     * 当前会话上下文快照。
     */
    private SessionContext sessionContext;

    /**
     * 当前画像快照。
     */
    private UserProfileDTO profileSnapshot;

    /**
     * 当前记忆快照。
     */
    private LayeredMemories memorySnapshot;

    /**
     * 路由器触发判定结果。
     */
    private TaskTriggerDecision triggerDecision;

    /**
     * 调用侧附加约束。
     */
    private AgentTaskConstraints constraints;
}
