package com.health.agent.dto.patient;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提供给医生工作台和治理系统消费的内部载荷。
 *
 * <p>这份对象强调审计、复核、风险展示和待办承接，不直接面向患者输出。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorWorkbenchPayload {
    /**
     * 这次任务绑定的 Agent 会话 ID。
     */
    private String agentSessionId;

    /**
     * 工作台视角下的患者来源任务 ID。
     */
    private String taskId;

    /**
     * 对应的患者主对话会话 ID。
     */
    private String conversationId;

    /**
     * 跨服务链路追踪 ID。
     */
    private String correlationId;

    /**
     * 本次任务来源。
     */
    private String source;

    /**
     * 工作台/治理系统使用的审计标签。
     */
    private String auditTag;

    /**
     * 当前复核状态，例如自动完成或待人工复核。
     */
    private String reviewStatus;

    /**
     * 实际执行路径，帮助区分真实 Agent 产物和模板兜底产物。
     */
    private String executionPath;

    /**
     * 真实 Agent 原始执行状态。
     */
    private String agentExecutionStatus;

    /**
     * 若发生模板兜底，这里记录回退原因。
     */
    private String fallbackReason;

    /**
     * 对应任务类型。
     */
    private String taskType;

    /**
     * 当前任务的风险等级。
     */
    private String riskLevel;

    /**
     * 是否需要重点关注。
     */
    private Boolean needsAttention;

    /**
     * 是否需要医生人工复核。
     */
    private Boolean needsHumanReview;

    /**
     * 证据摘要拼接后的简短版本，方便列表页快速查看。
     */
    private String evidenceChainSummary;

    /**
     * 工作台需要展示的核心发现。
     */
    @Builder.Default
    private List<String> keyFindings = new ArrayList<>();

    /**
     * 工作台需要展示的建议动作。
     */
    @Builder.Default
    private List<String> recommendedActions = new ArrayList<>();

    /**
     * 证据来源列表。
     */
    @Builder.Default
    private List<String> evidenceSources = new ArrayList<>();

    /**
     * 明确标识来自 ai-chat-service 预加载快照的证据来源。
     */
    @Builder.Default
    private List<String> upstreamEvidenceSources = new ArrayList<>();

    /**
     * 明确标识来自本轮 Agent 工具执行的证据来源。
     */
    @Builder.Default
    private List<String> agentEvidenceSources = new ArrayList<>();

    /**
     * 明确标识基于事实形成的推理/归纳证据。
     */
    @Builder.Default
    private List<String> inferenceEvidence = new ArrayList<>();

    /**
     * 仍待人工判断的不确定项。
     */
    @Builder.Default
    private List<String> uncertainties = new ArrayList<>();

    /**
     * 医生工作台可直接展示的结构化证据明细。
     */
    @Builder.Default
    private List<EvidenceDetail> evidenceDetails = new ArrayList<>();

    /**
     * 患者侧实际可见的证据卡片，便于工作台审阅“患者看到了什么依据”。
     */
    @Builder.Default
    private List<PatientVisibleEvidence> patientVisibleEvidence = new ArrayList<>();

    /**
     * 如需审批或人工接管，这里挂待处理任务摘要。
     */
    private DoctorAssistantPendingTaskPayload pendingTask;
}
