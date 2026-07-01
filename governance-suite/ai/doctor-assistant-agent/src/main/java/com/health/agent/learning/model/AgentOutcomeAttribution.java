package com.health.agent.learning.model;

import java.util.List;
import java.util.Map;

import com.health.agent.learning.enums.AgentPrimaryCause;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 运行结果归因。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentOutcomeAttribution {

    private String runId;
    private String sessionId;
    private String scene;
    private String taskType;
    private AgentPrimaryCause primaryCause;
    private List<String> secondaryCauses;
    private Double confidence;
    private Map<String, Object> evidenceSummary;
}
