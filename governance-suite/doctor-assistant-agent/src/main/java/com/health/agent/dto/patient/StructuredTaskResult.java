package com.health.agent.dto.patient;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredTaskResult {
    private String summary;
    private String riskLevel;
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
