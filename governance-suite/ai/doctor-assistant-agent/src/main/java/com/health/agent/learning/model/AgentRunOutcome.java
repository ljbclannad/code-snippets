package com.health.agent.learning.model;

import com.health.agent.learning.enums.AgentOutcomeLabel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 运行结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunOutcome {

    private String runId;
    private String sessionId;
    private String scene;
    private String taskType;
    private String customerId;
    private String doctorId;
    private String finalStatus;
    private AgentOutcomeLabel outcomeLabel;
    private Boolean fallbackUsed;
    private Boolean manualReviewRequired;
    private Boolean manualOverride;
    private Boolean doctorAccepted;
    private Boolean doctorRejected;
    private Boolean evidenceSufficient;
    private Integer toolErrorCount;
    private Integer retryCount;
    private Long durationMs;
    private String errorCode;
    private String errorMessage;
}
