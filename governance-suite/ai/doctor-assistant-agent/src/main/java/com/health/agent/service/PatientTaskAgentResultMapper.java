package com.health.agent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.dto.patient.AgentTaskSource;
import com.health.agent.dto.patient.DoctorAssistantPendingTaskPayload;
import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskStatus;
import com.health.agent.dto.patient.DoctorWorkbenchPayload;
import com.health.agent.dto.patient.StructuredTaskResult;
import com.health.agent.model.AgentResult;
import com.health.agent.model.PendingTask;
import com.health.agent.model.ResultStatus;
import com.health.agent.model.StructuredFinalAnswer;

@Component
public class PatientTaskAgentResultMapper {

    private static final Set<String> UPSTREAM_EVIDENCE_SOURCES = Set.of(
            "AI_CHAT_SESSION_CONTEXT",
            "PROFILE_SNAPSHOT",
            "AI_CHAT_MEMORY",
            "TRIGGER_DECISION");

    public StructuredTaskResult toStructuredTaskResult(
        StructuredFinalAnswer finalAnswer,
        StructuredTaskResult fallback
    ) {
        return toStructuredTaskResult(finalAnswer, fallback, null);
    }

    public StructuredTaskResult toStructuredTaskResult(
        StructuredFinalAnswer finalAnswer,
        StructuredTaskResult fallback,
        AgentExecutionCommand command
    ) {
        if (finalAnswer == null) {
            return enrichWithUpstreamEvidence(fallback, command);
        }
        StructuredTaskResult merged = StructuredTaskResult.builder()
                .summary(defaultValue(finalAnswer.getSummary(), fallback != null ? fallback.getSummary() : ""))
                .riskLevel(defaultValue(finalAnswer.getRiskLevel(), fallback != null ? fallback.getRiskLevel() : "MEDIUM"))
                .needsAttention(finalAnswer.isNeedsAttention()
                        || (fallback != null && Boolean.TRUE.equals(fallback.getNeedsAttention())))
                .keyFindings(copyOrDefault(finalAnswer.getKeyFindings(),
                        fallback != null ? fallback.getKeyFindings() : List.of()))
                .recommendedActions(copyOrDefault(finalAnswer.getRecommendedActions(),
                        fallback != null ? fallback.getRecommendedActions() : List.of()))
                .uncertainties(copyOrDefault(finalAnswer.getUncertainties(),
                        fallback != null ? fallback.getUncertainties() : List.of()))
                .supportingEvidence(copyOrDefault(finalAnswer.getSupportingEvidence(),
                        fallback != null ? fallback.getSupportingEvidence() : List.of()))
                .evidenceSources(copyOrDefault(finalAnswer.getEvidenceSources(),
                        fallback != null ? fallback.getEvidenceSources() : List.of()))
                .inferences(copyOrDefault(finalAnswer.getInferences(),
                        fallback != null ? fallback.getInferences() : List.of()))
                .build();
        return enrichWithUpstreamEvidence(merged, command);
    }

    public DoctorAssistantPendingTaskPayload toPendingTaskPayload(PendingTask pendingTask, String taskId) {
        if (pendingTask == null) {
            return null;
        }
        return DoctorAssistantPendingTaskPayload.builder()
                .pendingTaskId(pendingTask.getId() != null ? pendingTask.getId() : Math.abs(taskId.hashCode()) + 0L)
                .agentSessionId(pendingTask.getAgentSessionId())
                .toolName(pendingTask.getToolName())
                .toolDescription(pendingTask.getToolDescription())
                .reason(pendingTask.getReason())
                .status(pendingTask.getStatus())
                .reviewComment(pendingTask.getReviewComment())
                .expireAt(pendingTask.getExpireAt() != null
                        ? pendingTask.getExpireAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        : Instant.now().plusSeconds(3600).toEpochMilli())
                .build();
    }

    public DoctorWorkbenchPayload toDoctorWorkbenchPayload(
        DoctorAssistantTaskRequest request,
        AgentExecutionCommand command,
        String taskId,
        String agentSessionId,
        StructuredTaskResult structuredResult,
        boolean needsHumanReview,
        DoctorAssistantPendingTaskPayload pendingTaskPayload,
        String executionPath,
        String agentExecutionStatus,
        String fallbackReason
    ) {
        return DoctorWorkbenchPayload.builder()
                .agentSessionId(agentSessionId)
                .taskId(taskId)
                .conversationId(request.getConversationId())
                .correlationId(request.getCorrelationId())
                .source(command.getSource() == null ? AgentTaskSource.PATIENT_DIALOG_AI_CHAT.name() : command.getSource().name())
                .auditTag("PATIENT_DIALOG_AGENT_TASK")
                .reviewStatus(needsHumanReview ? "PENDING_REVIEW" : "AUTO_COMPLETED")
                .executionPath(executionPath)
                .agentExecutionStatus(agentExecutionStatus)
                .fallbackReason(fallbackReason)
                .taskType(command.getTaskType().name())
                .riskLevel(structuredResult.getRiskLevel())
                .needsAttention(structuredResult.getNeedsAttention())
                .needsHumanReview(needsHumanReview)
                .evidenceChainSummary(String.join(" | ", structuredResult.getSupportingEvidence()))
                .keyFindings(structuredResult.getKeyFindings())
                .recommendedActions(structuredResult.getRecommendedActions())
                .evidenceSources(structuredResult.getEvidenceSources())
                .upstreamEvidenceSources(filterEvidenceSources(structuredResult.getEvidenceSources(), true))
                .agentEvidenceSources(filterEvidenceSources(structuredResult.getEvidenceSources(), false))
                .inferenceEvidence(copyOrDefault(structuredResult.getInferences(), List.of()))
                .uncertainties(structuredResult.getUncertainties())
                .pendingTask(pendingTaskPayload)
                .build();
    }

    public DoctorAssistantTaskStatus toTaskStatus(AgentResult agentResult, boolean needsHumanReview) {
        if (needsHumanReview) {
            return DoctorAssistantTaskStatus.PENDING_APPROVAL;
        }
        if (agentResult == null || agentResult.getStatus() == null) {
            return DoctorAssistantTaskStatus.ERROR;
        }
        return switch (agentResult.getStatus()) {
            case SUCCESS -> DoctorAssistantTaskStatus.SUCCESS;
            case PENDING_APPROVAL -> DoctorAssistantTaskStatus.PENDING_APPROVAL;
            case ERROR, MAX_STEPS -> DoctorAssistantTaskStatus.ERROR;
        };
    }

    private List<String> copyOrDefault(List<String> primary, List<String> fallback) {
        List<String> source = primary != null && !primary.isEmpty() ? primary : fallback;
        return new ArrayList<>(source == null ? List.of() : source);
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private StructuredTaskResult enrichWithUpstreamEvidence(
            StructuredTaskResult base,
            AgentExecutionCommand command) {
        if (base == null) {
            return null;
        }

        List<String> supportingEvidence = copyOrDefault(base.getSupportingEvidence(), List.of());
        List<String> evidenceSources = copyOrDefault(base.getEvidenceSources(), List.of());

        if (command != null) {
            List<String> upstreamEvidence = buildUpstreamSupportingEvidence(command);
            List<String> upstreamSources = buildUpstreamEvidenceSources(command);
            if (supportingEvidence.isEmpty() || supportingEvidence.size() < 2) {
                supportingEvidence = mergeUnique(supportingEvidence, upstreamEvidence);
            }
            evidenceSources = mergeUnique(evidenceSources, upstreamSources);
        }

        base.setSupportingEvidence(supportingEvidence);
        base.setEvidenceSources(evidenceSources);
        if (base.getInferences() == null) {
            base.setInferences(new ArrayList<>());
        }
        return base;
    }

    private List<String> buildUpstreamSupportingEvidence(AgentExecutionCommand command) {
        List<String> evidence = new ArrayList<>();
        if (command == null) {
            return evidence;
        }
        if (command.getSessionContext() != null && !command.getSessionContext().isEmpty()) {
            evidence.add("上游会话上下文已预加载: " + summarizeMap(command.getSessionContext(), 2));
        }
        if (command.getProfileSnapshot() != null && !command.getProfileSnapshot().isEmpty()) {
            evidence.add("患者画像快照已预加载: " + summarizeMap(command.getProfileSnapshot(), 2));
        }
        if (command.getMemorySnapshot() != null) {
            if (command.getMemorySnapshot().getShortTermMemories() != null
                    && !command.getMemorySnapshot().getShortTermMemories().isEmpty()) {
                evidence.add("短期记忆已预加载: "
                        + summarizeList(command.getMemorySnapshot().getShortTermMemories(), 2));
            }
            if (command.getMemorySnapshot().getLongTermMemories() != null
                    && !command.getMemorySnapshot().getLongTermMemories().isEmpty()) {
                evidence.add("长期记忆已预加载: "
                        + summarizeList(command.getMemorySnapshot().getLongTermMemories(), 1));
            }
        }
        if (command.getTriggerDecision() != null && !command.getTriggerDecision().isEmpty()) {
            evidence.add("上游触发决策已命中: " + summarizeMap(command.getTriggerDecision(), 2));
        }
        return evidence;
    }

    private List<String> buildUpstreamEvidenceSources(AgentExecutionCommand command) {
        List<String> sources = new ArrayList<>();
        if (command == null) {
            return sources;
        }
        if (command.getSessionContext() != null && !command.getSessionContext().isEmpty()) {
            sources.add("AI_CHAT_SESSION_CONTEXT");
        }
        if (command.getProfileSnapshot() != null && !command.getProfileSnapshot().isEmpty()) {
            sources.add("PROFILE_SNAPSHOT");
        }
        if (command.getMemorySnapshot() != null
                && ((command.getMemorySnapshot().getShortTermMemories() != null
                        && !command.getMemorySnapshot().getShortTermMemories().isEmpty())
                || (command.getMemorySnapshot().getLongTermMemories() != null
                        && !command.getMemorySnapshot().getLongTermMemories().isEmpty()))) {
            sources.add("AI_CHAT_MEMORY");
        }
        if (command.getTriggerDecision() != null && !command.getTriggerDecision().isEmpty()) {
            sources.add("TRIGGER_DECISION");
        }
        return sources;
    }

    private List<String> mergeUnique(List<String> primary, List<String> additions) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(copyOrDefault(primary, List.of()));
        if (additions != null) {
            merged.addAll(additions.stream()
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .toList());
        }
        return new ArrayList<>(merged);
    }

    private List<String> filterEvidenceSources(List<String> evidenceSources, boolean upstream) {
        return copyOrDefault(evidenceSources, List.of()).stream()
                .filter(source -> upstream == UPSTREAM_EVIDENCE_SOURCES.contains(source))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String summarizeMap(java.util.Map<String, Object> source, int maxEntries) {
        return source.entrySet().stream()
                .limit(Math.max(1, maxEntries))
                .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
                .collect(Collectors.joining(", "));
    }

    private String summarizeList(List<?> source, int maxItems) {
        return source.stream()
                .limit(Math.max(1, maxItems))
                .map(String::valueOf)
                .collect(Collectors.joining(" | "));
    }
}
