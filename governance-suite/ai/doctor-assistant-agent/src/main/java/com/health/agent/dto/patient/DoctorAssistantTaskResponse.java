package com.health.agent.dto.patient;

import com.lx.governance.agentplatform.model.WorkbenchPayload;
import com.lx.governance.agentplatform.model.PlatformTraceContext;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * doctor-assistant-agent 返回给 ai-chat-service 的统一任务响应。
 *
 * <p>它把患者安全回复、结构化分析结果、工作台载荷以及回退信息收敛在一起，
 * 让上游既能继续患者对话主链路，也能把治理信息交给医生侧消费。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorAssistantTaskResponse {
    /**
     * 这次任务最终落到的 Agent 会话 ID。
     * 真实 Agent 执行和模板兜底都会尽量生成一个可追踪会话标识。
     */
    private String agentSessionId;

    /**
     * 患者来源任务 ID，用于一次升级任务的唯一标识。
     */
    private String taskId;

    /**
     * 回传原患者主对话会话 ID，便于上游把结果并回对话主链路。
     */
    private String conversationId;

    /**
     * 回传链路追踪 ID，便于跨服务排查。
     */
    private String correlationId;

    /**
     * 当前任务对上游可见的最终状态。
     */
    private DoctorAssistantTaskStatus status;

    /**
     * 本次返回对应的任务类型。
     */
    private TaskType taskType;

    /**
     * 面向患者的最终安全回复。
     * 这是上游真正会并回患者对话主链路的文案。
     */
    private String patientSafeReply;

    /**
     * 面向患者的证据卡片，与 patientSafeReply 对齐但不暴露医生内部细节。
     */
    private java.util.List<PatientVisibleEvidence> patientVisibleEvidence;

    /**
     * 面向医生/系统消费的结构化分析结果。
     */
    private StructuredTaskResult structuredResult;

    /**
     * 面向工作台和治理系统的内部载荷。
     */
    private DoctorWorkbenchPayload doctorWorkbenchPayload;

    /**
     * 面向平台统一工作台协议的共享载荷。
     */
    private WorkbenchPayload platformWorkbenchPayload;

    /**
     * 平台统一 trace 上下文。
     */
    private PlatformTraceContext traceContext;

    /**
     * 实际执行路径，例如真实 Agent 成功或模板兜底。
     */
    private String executionPath;

    /**
     * 真实 Agent 的原始执行状态，便于区分“Agent 真成功”和“fallback 后成功”。
     */
    private String agentExecutionStatus;

    /**
     * 若发生模板回退，这里记录回退原因。
     */
    private String fallbackReason;

    /**
     * 是否需要医生人工复核。
     */
    private Boolean needsHumanReview;

    /**
     * 若进入待审批/待复核链路，这里返回摘要信息。
     */
    private DoctorAssistantPendingTaskPayload pendingTask;

    /**
     * 失败或降级时的错误信息。
     */
    private String errorMessage;
}
