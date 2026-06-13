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
public class DoctorWorkbenchPayload {
    private String agentSessionId;
    private String taskId;
    private String conversationId;
    private String correlationId;
    private String source;
    private String auditTag;
    private String reviewStatus;
    private String taskType;
    private String riskLevel;
    private Boolean needsAttention;
    private Boolean needsHumanReview;
    private String evidenceChainSummary;

    @Builder.Default
    private List<String> keyFindings = new ArrayList<>();

    @Builder.Default
    private List<String> recommendedActions = new ArrayList<>();

    @Builder.Default
    private List<String> evidenceSources = new ArrayList<>();

    @Builder.Default
    private List<String> uncertainties = new ArrayList<>();

    private DoctorAssistantPendingTaskPayload pendingTask;
}
