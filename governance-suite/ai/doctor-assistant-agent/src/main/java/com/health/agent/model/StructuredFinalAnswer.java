package com.health.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 结构化最终输出
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredFinalAnswer {

    /**
     * 最终结论摘要，是结构化结果里最浓缩的一层。
     */
    private String summary;

    /**
     * 支撑最终结论的核心发现。
     */
    private List<String> keyFindings;

    /**
     * 最终结论给出的风险等级。
     */
    private String riskLevel;

    /**
     * 建议执行的后续动作。
     */
    private List<String> recommendedActions;

    /**
     * 是否提示本次任务需要重点关注。
     */
    private boolean needsAttention;

    /**
     * 当前仍无法完全确认的不确定项。
     */
    private List<String> uncertainties;

    /**
     * 支撑当前结论的证据摘要。
     */
    private List<String> supportingEvidence;

    /**
     * 证据来源列表。
     */
    private List<String> evidenceSources;

    /**
     * 在事实基础上形成的归纳判断。
     */
    private List<String> inferences;

    /**
     * 结构化证据明细，优先用于工作台/审计链路。
     */
    private List<StructuredEvidenceItem> evidenceDetails;

    public String toTextSummary() {
        String findings = keyFindings == null || keyFindings.isEmpty()
            ? "无"
            : String.join("；", keyFindings);
        String actions = recommendedActions == null || recommendedActions.isEmpty()
            ? "无"
            : String.join("；", recommendedActions);
        String unknowns = uncertainties == null || uncertainties.isEmpty()
            ? "无"
            : String.join("；", uncertainties);
        String evidence = supportingEvidence == null || supportingEvidence.isEmpty()
            ? "无"
            : String.join("；", supportingEvidence);
        String sources = evidenceSources == null || evidenceSources.isEmpty()
            ? "无"
            : String.join("；", evidenceSources);
        String inferencesText = inferences == null || inferences.isEmpty()
            ? "无"
            : String.join("；", inferences);

        return """
            问题摘要: %s
            关键发现: %s
            风险等级: %s
            建议动作: %s
            需要重点关注: %s
            不确定项: %s
            支撑证据: %s
            证据来源: %s
            归纳判断: %s
            """.formatted(
            summary == null ? "" : summary,
            findings,
            riskLevel == null ? "UNKNOWN" : riskLevel,
            actions,
            needsAttention ? "是" : "否",
            unknowns,
            evidence,
            sources,
            inferencesText
        ).trim();
    }
}
