package com.lx.governance.agentplatform.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台统一工作台载荷。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkbenchPayload {

    private String runId;

    private String taskId;

    private String conversationId;

    private String correlationId;

    private String source;

    private String taskType;

    private WorkbenchViewType viewType;

    private String reviewStatus;

    private String executionPath;

    private String agentExecutionStatus;

    private String fallbackReason;

    private String riskLevel;

    private Boolean needsAttention;

    private Boolean needsHumanReview;

    private String title;

    private String summary;

    private String evidenceSummary;

    @Builder.Default
    private List<String> keyFindings = new ArrayList<>();

    @Builder.Default
    private List<String> recommendedActions = new ArrayList<>();

    @Builder.Default
    private List<String> evidenceSources = new ArrayList<>();

    @Builder.Default
    private List<WorkbenchAction> availableActions = new ArrayList<>();

    private WorkbenchPendingTask pendingTask;

    @Builder.Default
    private Map<String, Object> viewData = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> extensions = new LinkedHashMap<>();
}
