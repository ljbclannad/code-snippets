package com.health.agent.dto.patient;

import java.util.Map;

import com.health.agent.dto.agent.AgentExecutionCommand;

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
    /**
     * 当前患者消息被路由命中的任务类型，决定后续分析目标和患者回复风格。
     */
    @NotNull(message = "taskType 不能为空")
    private TaskType taskType;

    /**
     * 上游为本次任务生成的自然语言目标。
     * 真实 Agent 接入后会把它作为主推理目标使用。
     */
    @Size(max = 500, message = "goal 长度不能超过 500")
    private String goal;

    /**
     * 患者 ID，也是本次任务绑定的业务主体。
     */
    @NotBlank(message = "customerId 不能为空")
    private String customerId;

    /**
     * 当前负责该患者任务的医生或工作台医生 ID。
     */
    @NotBlank(message = "doctorId 不能为空")
    private String doctorId;

    /**
     * 请求来源，用于区分这次调用来自患者对话链路还是其他入口。
     */
    private AgentTaskSource source;

    /**
     * 当前轮患者原始消息，是患者侧分析和安全识别的第一手输入。
     */
    @NotBlank(message = "patientMessage 不能为空")
    @Size(max = 2000, message = "patientMessage 长度不能超过 2000")
    private String patientMessage;

    /**
     * 患者主对话会话 ID，用于把任务结果挂回原会话。
     */
    @NotBlank(message = "conversationId 不能为空")
    private String conversationId;

    /**
     * 本次调用链路追踪 ID，用于串联 ai-chat-service 与 doctor-assistant-agent 日志。
     */
    @NotBlank(message = "correlationId 不能为空")
    private String correlationId;

    /**
     * ai-chat-service 侧当前会话上下文快照，例如话题、阶段、意图等。
     */
    private Map<String, Object> sessionContext;

    /**
     * ai-chat-service 已拿到的患者画像快照，避免下游重复补齐基础事实。
     */
    private Map<String, Object> profileSnapshot;

    /**
     * ai-chat-service 已加载的短期/长期记忆快照。
     */
    private MemorySnapshot memorySnapshot;

    /**
     * 上游任务路由器的触发判定结果，便于下游复盘为什么会升级到 Agent。
     */
    private Map<String, Object> triggerDecision;

    /**
     * 上游附加的调用约束，例如是否要求证据、是否允许失败回退等。
     */
    private AgentTaskConstraints constraints;

    public AgentExecutionCommand toExecutionCommand() {
        return AgentExecutionCommand.builder()
                .goal(goal)
                .customerId(customerId)
                .doctorId(doctorId)
                .conversationId(conversationId)
                .correlationId(correlationId)
                .source(source)
                .taskType(taskType)
                .patientMessage(patientMessage)
                .sessionContext(sessionContext)
                .profileSnapshot(profileSnapshot)
                .memorySnapshot(memorySnapshot)
                .triggerDecision(triggerDecision)
                .constraints(constraints)
                .build();
    }
}
