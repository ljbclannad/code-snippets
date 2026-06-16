package com.health.agent.dto.patient;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * doctor-assistant-agent 输出给医生侧和治理侧的结构化分析结果。
 *
 * <p>它与患者安全回复分离，专门承载结论、证据和建议动作，方便工作台展示、
 * 人工复核以及后续治理链路复用。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredTaskResult {
    /**
     * 面向医生/系统的摘要结论。
     */
    private String summary;

    /**
     * 当前结构化结果给出的风险等级。
     */
    private String riskLevel;

    /**
     * 是否提示这次任务需要重点关注。
     */
    private Boolean needsAttention;

    /**
     * 最核心的事实发现列表。
     */
    @Builder.Default
    private List<String> keyFindings = new ArrayList<>();

    /**
     * 建议的下一步动作列表。
     */
    @Builder.Default
    private List<String> recommendedActions = new ArrayList<>();

    /**
     * 当前仍然存在的不确定项。
     */
    @Builder.Default
    private List<String> uncertainties = new ArrayList<>();

    /**
     * 支撑当前结论的证据摘要。
     */
    @Builder.Default
    private List<String> supportingEvidence = new ArrayList<>();

    /**
     * 证据来源标识，便于回溯是会话上下文、画像还是工具结果。
     */
    @Builder.Default
    private List<String> evidenceSources = new ArrayList<>();

    /**
     * 在事实之上的归纳判断，通常比 keyFindings 更偏结论层。
     */
    @Builder.Default
    private List<String> inferences = new ArrayList<>();
}
