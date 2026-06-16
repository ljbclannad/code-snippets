package com.lx.governance.aichat.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提供给医生工作台或治理链路的内部载荷。
 *
 * <p>患者侧最终只消费 {@code patientSafeReply}，而这份对象承载的是
 * 医生端和治理端需要的执行轨迹、风险摘要、证据来源与待处理事项。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorWorkbenchPayload {

    /**
     * 对应的 Agent 会话 ID。
     */
    private String agentSessionId;
    /**
     * 工作台视角下的任务 ID。
     */
    private String taskId;
    /**
     * 原患者主对话会话 ID。
     */
    private String conversationId;
    /**
     * 跨服务链路追踪 ID。
     */
    private String correlationId;
    /**
     * 任务来源入口。
     */
    private String source;
    /**
     * 审计或治理标签。
     */
    private String auditTag;
    /**
     * 当前复核状态。
     */
    private String reviewStatus;
    /**
     * 实际执行路径，例如 agent_success 或 template_fallback。
     */
    private String executionPath;
    /**
     * 真实 Agent 的原始执行状态。
     */
    private String agentExecutionStatus;
    /**
     * 发生模板兜底时的原因。
     */
    private String fallbackReason;
    /**
     * 对应任务类型。
     */
    private String taskType;
    /**
     * 当前任务风险等级。
     */
    private String riskLevel;
    /**
     * 是否需要重点关注。
     */
    private Boolean needsAttention;
    /**
     * 是否需要人工复核。
     */
    private Boolean needsHumanReview;
    /**
     * 证据链摘要，方便工作台列表快速展示。
     */
    private String evidenceChainSummary;

    @Builder.Default
    private List<String> keyFindings = new ArrayList<>();

    @Builder.Default
    private List<String> recommendedActions = new ArrayList<>();

    @Builder.Default
    private List<String> evidenceSources = new ArrayList<>();

    @Builder.Default
    private List<String> uncertainties = new ArrayList<>();

    /**
     * 如进入审批/接管流程，这里附带待处理任务摘要。
     */
    private DoctorAssistantPendingTaskPayload pendingTask;
}
