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

    private String summary;

    private List<String> keyFindings;

    private String riskLevel;

    private List<String> recommendedActions;

    private boolean needsAttention;

    private List<String> uncertainties;

    private List<String> supportingEvidence;

    private List<String> evidenceSources;

    private List<String> inferences;

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
