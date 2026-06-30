package com.health.agent.learning.model;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 运行策略快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunStrategySnapshot {

    private String runId;
    private String sessionId;
    private String scene;
    private String taskType;
    private String promptVersion;
    private String selectorVersion;
    private List<String> selectedAgentIds;
    private String selectionMode;
    private String toolCatalogVersion;
    private List<String> toolSequence;
    private Map<String, Object> memoryProfile;
    private Integer memoryRetrievalCount;
    private Boolean memoryRerankApplied;
    private String degradationLevel;
    private Boolean parallelEnabled;
    private Boolean fallbackAllowed;
    private Integer timeoutMillis;
    private Boolean patientVisibleOnly;
}
