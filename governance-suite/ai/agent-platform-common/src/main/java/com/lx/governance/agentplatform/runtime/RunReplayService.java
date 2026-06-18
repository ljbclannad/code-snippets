package com.lx.governance.agentplatform.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.lx.governance.agentplatform.model.ApprovalTask;
import com.lx.governance.agentplatform.model.ArtifactRecord;
import com.lx.governance.agentplatform.model.ConversationRunLink;
import com.lx.governance.agentplatform.model.ReplayRouteDecision;
import com.lx.governance.agentplatform.model.ReplayToolCall;
import com.lx.governance.agentplatform.model.Run;
import com.lx.governance.agentplatform.model.RunEvent;
import com.lx.governance.agentplatform.model.RunPersistenceSnapshot;
import com.lx.governance.agentplatform.model.RunReplayView;
import com.lx.governance.agentplatform.model.RunStep;

/**
 * 平台统一 run 回放聚合服务。
 */
public class RunReplayService {

    private final RunPersistenceRepository runPersistenceRepository;
    private final RunRepository runRepository;
    private final RunEventRepository runEventRepository;
    private final ArtifactRepository artifactRepository;
    private final ConversationRunLinkRepository conversationRunLinkRepository;

    public RunReplayService(
            RunPersistenceRepository runPersistenceRepository,
            RunRepository runRepository,
            RunEventRepository runEventRepository,
            ArtifactRepository artifactRepository,
            ConversationRunLinkRepository conversationRunLinkRepository) {
        this.runPersistenceRepository = runPersistenceRepository;
        this.runRepository = runRepository;
        this.runEventRepository = runEventRepository;
        this.artifactRepository = artifactRepository;
        this.conversationRunLinkRepository = conversationRunLinkRepository;
    }

    public Optional<RunReplayView> replayByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }

        Optional<RunPersistenceSnapshot> snapshotOptional = runPersistenceRepository.findSnapshotByRunId(runId);
        Optional<Run> runOptional = runRepository.findRunById(runId);
        if (snapshotOptional.isEmpty() && runOptional.isEmpty()) {
            return Optional.empty();
        }

        RunPersistenceSnapshot snapshot = snapshotOptional.orElse(null);
        Run run = runOptional.orElseGet(() -> snapshot != null ? snapshot.getRun() : null);
        if (run == null) {
            return Optional.empty();
        }

        List<RunStep> steps = sortSteps(run.getSteps());
        List<RunEvent> events = snapshot != null && snapshot.getRun() != null && snapshot.getRun().getEvents() != null
                ? sortEvents(snapshot.getRun().getEvents())
                : sortEvents(runEventRepository.findEventsByRunId(runId));
        List<ApprovalTask> approvalTasks = snapshot != null && snapshot.getApprovalTasks() != null
                ? new ArrayList<>(snapshot.getApprovalTasks())
                : List.of();
        List<ArtifactRecord> artifacts = snapshot != null && snapshot.getArtifacts() != null
                ? new ArrayList<>(snapshot.getArtifacts())
                : artifactRepository.findArtifactsByRunId(runId);
        ConversationRunLink conversationLink = conversationRunLinkRepository.findByRunId(runId).orElse(null);

        RunReplayView.RunReplayViewBuilder builder = RunReplayView.builder()
                .runId(runId)
                .run(run)
                .conversationLink(conversationLink)
                .routeDecision(buildRouteDecision(conversationLink))
                .legacyRunTable(snapshot != null ? snapshot.getLegacyRunTable() : null)
                .legacyRunId(snapshot != null ? snapshot.getLegacyRunId() : null)
                .legacyApprovalTable(snapshot != null ? snapshot.getLegacyApprovalTable() : null);

        steps.forEach(builder::step);
        events.forEach(builder::event);
        approvalTasks.forEach(builder::approvalTask);
        artifacts.forEach(builder::artifact);
        buildToolCalls(run, approvalTasks).forEach(builder::toolCall);
        if (snapshot != null && snapshot.getLegacyApprovalIds() != null) {
            snapshot.getLegacyApprovalIds().forEach(builder::legacyApprovalId);
        }
        return Optional.of(builder.build());
    }

    private ReplayRouteDecision buildRouteDecision(ConversationRunLink conversationLink) {
        if (conversationLink == null) {
            return null;
        }
        return ReplayRouteDecision.builder()
                .entryMode(conversationLink.getEntryMode())
                .outcomeMode(conversationLink.getOutcomeMode())
                .triggerSource(conversationLink.getTriggerSource())
                .fallbackReason(conversationLink.getFallbackReason())
                .build();
    }

    private List<ReplayToolCall> buildToolCalls(Run run, List<ApprovalTask> approvalTasks) {
        if (approvalTasks == null || approvalTasks.isEmpty()) {
            return List.of();
        }
        String resultSummary = extractResultSummary(run);
        return approvalTasks.stream()
                .map(task -> ReplayToolCall.builder()
                        .toolCallId(task.getApprovalTaskId())
                        .runId(task.getRunId())
                        .stepId(resolveStepId(run))
                        .toolName(task.getTaskKey())
                        .toolDescription(task.getTaskName())
                        .status(task.getStatus() != null ? task.getStatus().name() : null)
                        .reason(task.getReason())
                        .resultSummary(resultSummary)
                        .createdAt(task.getCreatedAt())
                        .expireAt(task.getExpireAt())
                        .build())
                .toList();
    }

    private String extractResultSummary(Run run) {
        if (run == null || run.getOutput() == null) {
            return null;
        }
        Object finalAnswer = run.getOutput().get("finalAnswer");
        if (finalAnswer != null) {
            return String.valueOf(finalAnswer);
        }
        Object errorMessage = run.getOutput().get("errorMessage");
        return errorMessage != null ? String.valueOf(errorMessage) : null;
    }

    private String resolveStepId(Run run) {
        if (run == null || run.getSteps() == null || run.getSteps().isEmpty()) {
            return null;
        }
        List<RunStep> sortedSteps = sortSteps(run.getSteps());
        return sortedSteps.get(sortedSteps.size() - 1).getStepId();
    }

    private List<RunStep> sortSteps(List<RunStep> steps) {
        if (steps == null) {
            return List.of();
        }
        return steps.stream()
                .sorted(Comparator.comparing(RunStep::getStepIndex, Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    private List<RunEvent> sortEvents(List<RunEvent> events) {
        if (events == null) {
            return List.of();
        }
        return events.stream()
                .sorted(Comparator.comparing(RunEvent::getCreatedAt, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }
}
