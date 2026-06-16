package com.lx.governance.aichat.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ai-chat-service 侧消费的结构化分析结果。
 *
 * <p>它是 doctor-assistant-agent 对患者任务的“医生/系统可读结论层”，
 * 与患者安全回复分离，便于工作台展示、人工复核和后续治理。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredTaskResult {

    /**
     * 任务结论摘要。
     */
    private String summary;
    /**
     * 归一化后的风险等级。
     */
    private String riskLevel;
    /**
     * 是否需要重点关注。
     */
    private Boolean needsAttention;

    @Builder.Default
    private List<String> keyFindings = new ArrayList<>();

    @Builder.Default
    private List<String> recommendedActions = new ArrayList<>();

    @Builder.Default
    private List<String> uncertainties = new ArrayList<>();

    @Builder.Default
    private List<String> supportingEvidence = new ArrayList<>();

    @Builder.Default
    private List<String> evidenceSources = new ArrayList<>();

    @Builder.Default
    private List<String> inferences = new ArrayList<>();
}
