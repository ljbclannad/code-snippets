package com.health.agent.service.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import com.health.agent.dto.patient.EvidenceDetail;
import com.health.agent.dto.patient.PatientVisibleEvidence;
import com.health.agent.dto.patient.StructuredTaskResult;
import com.health.agent.model.AgentResult;
import com.health.agent.model.PendingTask;
import com.health.agent.model.ResultStatus;
import com.health.agent.model.StructuredEvidenceItem;
import com.health.agent.model.StructuredFinalAnswer;
import com.lx.governance.agentplatform.model.WorkbenchAction;
import com.lx.governance.agentplatform.model.WorkbenchActionType;
import com.lx.governance.agentplatform.model.PlatformTraceContext;
import com.lx.governance.agentplatform.model.WorkbenchPayload;
import com.lx.governance.agentplatform.model.WorkbenchPendingTask;
import com.lx.governance.agentplatform.model.WorkbenchViewType;

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
                .evidenceDetails(copyEvidenceDetails(finalAnswer.getEvidenceDetails(),
                        fallback != null ? fallback.getEvidenceDetails() : List.of()))
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
        List<PatientVisibleEvidence> patientVisibleEvidence,
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
                .evidenceDetails(toEvidenceDetails(structuredResult))
                .patientVisibleEvidence(copyPatientVisibleEvidence(patientVisibleEvidence))
                .pendingTask(pendingTaskPayload)
                .build();
    }

    public WorkbenchPayload toPlatformWorkbenchPayload(
            DoctorAssistantTaskRequest request,
            AgentExecutionCommand command,
            String taskId,
            String runId,
            StructuredTaskResult structuredResult,
            boolean needsHumanReview,
            DoctorAssistantPendingTaskPayload pendingTaskPayload,
            String executionPath,
            String agentExecutionStatus,
            String fallbackReason) {
        return WorkbenchPayload.builder()
                .runId(runId)
                .taskId(taskId)
                .conversationId(request.getConversationId())
                .correlationId(request.getCorrelationId())
                .source(command.getSource() == null ? AgentTaskSource.PATIENT_DIALOG_AI_CHAT.name() : command.getSource().name())
                .taskType(command.getTaskType().name())
                .viewType(resolveViewType(needsHumanReview, pendingTaskPayload))
                .reviewStatus(needsHumanReview ? "PENDING_REVIEW" : "AUTO_COMPLETED")
                .executionPath(executionPath)
                .agentExecutionStatus(agentExecutionStatus)
                .fallbackReason(fallbackReason)
                .riskLevel(structuredResult.getRiskLevel())
                .needsAttention(structuredResult.getNeedsAttention())
                .needsHumanReview(needsHumanReview)
                .title(command.getTaskType().name())
                .summary(structuredResult.getSummary())
                .evidenceSummary(String.join(" | ", structuredResult.getSupportingEvidence()))
                .keyFindings(copyOrDefault(structuredResult.getKeyFindings(), List.of()))
                .recommendedActions(copyOrDefault(structuredResult.getRecommendedActions(), List.of()))
                .evidenceSources(copyOrDefault(structuredResult.getEvidenceSources(), List.of()))
                .availableActions(buildWorkbenchActions(needsHumanReview, pendingTaskPayload))
                .pendingTask(toWorkbenchPendingTask(pendingTaskPayload, runId))
                .viewData(buildViewData(structuredResult))
                .extensions(buildExtensions(structuredResult))
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

    public PlatformTraceContext toTraceContext(
            DoctorAssistantTaskRequest request,
            AgentExecutionCommand command,
            String runId,
            String agentId,
            String toolName) {
        PlatformTraceContext upstream = request != null ? request.getTraceContext() : null;
        return PlatformTraceContext.builder()
                .traceId(upstream != null ? upstream.getTraceId() : null)
                .conversationId(request != null ? request.getConversationId() : null)
                .correlationId(request != null ? request.getCorrelationId() : null)
                .runId(runId)
                .stepId(null)
                .agentId(agentId != null ? agentId : runId)
                .toolName(toolName)
                .build();
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
        List<StructuredEvidenceItem> evidenceDetails = copyEvidenceDetails(base.getEvidenceDetails(), List.of());
        if (evidenceDetails.isEmpty()) {
            evidenceDetails = synthesizeEvidenceDetails(supportingEvidence, evidenceSources);
        }
        if (command != null) {
            evidenceDetails = mergeEvidenceDetails(evidenceDetails, buildUpstreamEvidenceDetails(command));
        }
        base.setEvidenceDetails(evidenceDetails);
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

    private List<StructuredEvidenceItem> copyEvidenceDetails(
            List<StructuredEvidenceItem> primary,
            List<StructuredEvidenceItem> fallback) {
        List<StructuredEvidenceItem> source = primary != null && !primary.isEmpty() ? primary : fallback;
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        return source.stream()
                .filter(Objects::nonNull)
                .map(item -> StructuredEvidenceItem.builder()
                        .summary(item.getSummary())
                        .source(item.getSource())
                        .evidenceType(item.getEvidenceType())
                        .upstream(item.getUpstream())
                        .title(item.getTitle())
                        .snippet(item.getSnippet())
                        .trustLevel(item.getTrustLevel())
                        .score(item.getScore())
                        .hitReason(item.getHitReason())
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<StructuredEvidenceItem> buildUpstreamEvidenceDetails(AgentExecutionCommand command) {
        return synthesizeEvidenceDetails(
                buildUpstreamSupportingEvidence(command),
                buildUpstreamEvidenceSources(command));
    }

    private List<StructuredEvidenceItem> synthesizeEvidenceDetails(
            List<String> supportingEvidence,
            List<String> evidenceSources) {
        List<String> evidenceList = copyOrDefault(supportingEvidence, List.of());
        List<String> sourceList = copyOrDefault(evidenceSources, List.of());
        List<StructuredEvidenceItem> details = new ArrayList<>();

        for (int i = 0; i < evidenceList.size(); i++) {
            String summary = evidenceList.get(i);
            if (summary == null || summary.isBlank()) {
                continue;
            }
            String source = resolveEvidenceSource(sourceList, i);
            details.add(StructuredEvidenceItem.builder()
                    .summary(summary)
                    .source(source)
                    .evidenceType(isUpstreamSource(source) ? "UPSTREAM_CONTEXT" : "SUPPORTING_EVIDENCE")
                    .upstream(isUpstreamSource(source))
                    .build());
        }

        if (details.isEmpty()) {
            for (String source : sourceList) {
                if (source == null || source.isBlank()) {
                    continue;
                }
                details.add(StructuredEvidenceItem.builder()
                        .summary("证据来源: " + source)
                        .source(source)
                        .evidenceType(isUpstreamSource(source) ? "UPSTREAM_CONTEXT" : "SOURCE_ONLY")
                        .upstream(isUpstreamSource(source))
                        .build());
            }
        }
        return details;
    }

    private String resolveEvidenceSource(List<String> sources, int index) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        if (sources.size() == 1) {
            return sources.get(0);
        }
        if (index < sources.size()) {
            return sources.get(index);
        }
        return null;
    }

    private boolean isUpstreamSource(String source) {
        return source != null && UPSTREAM_EVIDENCE_SOURCES.contains(source);
    }

    private List<StructuredEvidenceItem> mergeEvidenceDetails(
            List<StructuredEvidenceItem> primary,
            List<StructuredEvidenceItem> additions) {
        List<StructuredEvidenceItem> merged = copyEvidenceDetails(primary, List.of());
        Set<String> identities = merged.stream()
                .map(this::evidenceIdentity)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (StructuredEvidenceItem item : copyEvidenceDetails(additions, List.of())) {
            if (identities.add(evidenceIdentity(item))) {
                merged.add(item);
            }
        }
        return merged;
    }

    private String evidenceIdentity(StructuredEvidenceItem item) {
        if (item == null) {
            return "";
        }
        return String.join("|",
                defaultValue(item.getSummary(), ""),
                defaultValue(item.getSource(), ""),
                defaultValue(item.getEvidenceType(), ""));
    }

    private WorkbenchViewType resolveViewType(boolean needsHumanReview, DoctorAssistantPendingTaskPayload pendingTaskPayload) {
        if (needsHumanReview && pendingTaskPayload != null) {
            return WorkbenchViewType.APPROVAL;
        }
        if (needsHumanReview) {
            return WorkbenchViewType.REVIEW;
        }
        return WorkbenchViewType.SUMMARY;
    }

    private List<WorkbenchAction> buildWorkbenchActions(
            boolean needsHumanReview,
            DoctorAssistantPendingTaskPayload pendingTaskPayload) {
        List<WorkbenchAction> actions = new ArrayList<>();
        if (needsHumanReview) {
            actions.add(WorkbenchAction.builder()
                    .actionType(WorkbenchActionType.APPROVE)
                    .label("审批通过")
                    .enabled(pendingTaskPayload != null)
                    .requiresComment(false)
                    .build());
            actions.add(WorkbenchAction.builder()
                    .actionType(WorkbenchActionType.REJECT)
                    .label("驳回")
                    .enabled(pendingTaskPayload != null)
                    .requiresComment(true)
                    .build());
            actions.add(WorkbenchAction.builder()
                    .actionType(WorkbenchActionType.REQUEST_INFO)
                    .label("补充信息")
                    .enabled(true)
                    .requiresComment(true)
                    .build());
        }
        actions.add(WorkbenchAction.builder()
                .actionType(WorkbenchActionType.RETRY)
                .label("重新执行")
                .enabled(true)
                .requiresComment(false)
                .build());
        return actions;
    }

    private WorkbenchPendingTask toWorkbenchPendingTask(
            DoctorAssistantPendingTaskPayload pendingTaskPayload,
            String runId) {
        if (pendingTaskPayload == null) {
            return null;
        }
        return WorkbenchPendingTask.builder()
                .approvalTaskId(String.valueOf(pendingTaskPayload.getPendingTaskId()))
                .runId(runId)
                .status(pendingTaskPayload.getStatus())
                .title(pendingTaskPayload.getToolDescription())
                .reason(pendingTaskPayload.getReason())
                .expireAt(pendingTaskPayload.getExpireAt())
                .build();
    }

    private Map<String, Object> buildViewData(StructuredTaskResult structuredResult) {
        Map<String, Object> viewData = new LinkedHashMap<>();
        viewData.put("inferenceEvidence", copyOrDefault(structuredResult.getInferences(), List.of()));
        viewData.put("uncertainties", copyOrDefault(structuredResult.getUncertainties(), List.of()));
        viewData.put("supportingEvidence", copyOrDefault(structuredResult.getSupportingEvidence(), List.of()));
        viewData.put("evidenceDetails", toEvidenceDetails(structuredResult));
        return viewData;
    }

    private Map<String, Object> buildExtensions(StructuredTaskResult structuredResult) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("upstreamEvidenceSources",
                filterEvidenceSources(structuredResult.getEvidenceSources(), true));
        extensions.put("agentEvidenceSources",
                filterEvidenceSources(structuredResult.getEvidenceSources(), false));
        extensions.put("evidenceDetails", toEvidenceDetails(structuredResult));
        return extensions;
    }

    private List<EvidenceDetail> toEvidenceDetails(StructuredTaskResult structuredResult) {
        List<StructuredEvidenceItem> items = copyEvidenceDetails(
                structuredResult.getEvidenceDetails(),
                synthesizeEvidenceDetails(structuredResult.getSupportingEvidence(), structuredResult.getEvidenceSources()));
        return items.stream()
                .map(item -> EvidenceDetail.builder()
                        .summary(item.getSummary())
                        .source(item.getSource())
                        .evidenceType(item.getEvidenceType())
                        .upstream(item.getUpstream())
                        .title(item.getTitle())
                        .snippet(item.getSnippet())
                        .trustLevel(item.getTrustLevel())
                        .score(item.getScore())
                        .hitReason(item.getHitReason())
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<PatientVisibleEvidence> copyPatientVisibleEvidence(List<PatientVisibleEvidence> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        return source.stream()
                .filter(Objects::nonNull)
                .map(item -> PatientVisibleEvidence.builder()
                        .title(item.getTitle())
                        .summary(item.getSummary())
                        .source(item.getSource())
                        .trustLevel(item.getTrustLevel())
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
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
