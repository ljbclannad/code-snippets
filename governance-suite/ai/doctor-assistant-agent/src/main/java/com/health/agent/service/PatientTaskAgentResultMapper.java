package com.health.agent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    public StructuredTaskResult toStructuredTaskResult(
        StructuredFinalAnswer finalAnswer,
        StructuredTaskResult fallback
    ) {
        if (finalAnswer == null) {
            return fallback;
        }
        return StructuredTaskResult.builder()
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
}
