package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 人工介入策略。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowManualInterventionPolicy {

    WorkflowManualInterventionType interventionType;

    String queueKey;

    String assigneeType;

    Long timeoutMs;

    String resumeNodeId;

    String rejectNodeId;
}
