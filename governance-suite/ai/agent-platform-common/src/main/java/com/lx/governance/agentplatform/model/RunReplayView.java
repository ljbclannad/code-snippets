package com.lx.governance.agentplatform.model;

import java.util.List;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台 run 回放视图。
 */
@Value
@Builder(toBuilder = true)
public class RunReplayView {

    String runId;

    Run run;

    ConversationRunLink conversationLink;

    ReplayRouteDecision routeDecision;

    @Singular("step")
    List<RunStep> steps;

    @Singular("event")
    List<RunEvent> events;

    @Singular("approvalTask")
    List<ApprovalTask> approvalTasks;

    @Singular("artifact")
    List<ArtifactRecord> artifacts;

    @Singular("toolCall")
    List<ReplayToolCall> toolCalls;

    String legacyRunTable;

    String legacyRunId;

    String legacyApprovalTable;

    @Singular("legacyApprovalId")
    List<String> legacyApprovalIds;
}
