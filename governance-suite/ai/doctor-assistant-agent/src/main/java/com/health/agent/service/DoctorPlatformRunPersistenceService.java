package com.health.agent.service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.health.agent.config.DoctorAgentPlatformProperties;
import com.health.agent.engine.ReActPromptBuilder;
import com.health.agent.model.AgentSession;
import com.health.agent.model.PendingTask;
import com.lx.governance.agentplatform.model.ApprovalStatus;
import com.lx.governance.agentplatform.model.ConversationRunLink;
import com.lx.governance.agentplatform.model.PlatformEntryMode;
import com.lx.governance.agentplatform.model.PlatformOutcomeMode;
import com.lx.governance.agentplatform.model.ApprovalTask;
import com.lx.governance.agentplatform.model.PlatformAuditEvent;
import com.lx.governance.agentplatform.model.PlatformAuditEventType;
import com.lx.governance.agentplatform.model.PlatformAuditOutputScope;
import com.lx.governance.agentplatform.model.RunPersistenceSnapshot;
import com.lx.governance.agentplatform.runtime.ConversationRunLinkRepository;
import com.lx.governance.agentplatform.runtime.PlatformAuditEventRepository;
import com.lx.governance.agentplatform.runtime.PlatformMetricsService;
import com.lx.governance.agentplatform.runtime.RunPersistenceRepository;

/**
 * 将 doctor 运行态投影到平台 run 主路径。
 */
@Service
public class DoctorPlatformRunPersistenceService {

    private final AgentSessionPlatformSnapshotMapper snapshotMapper;
    private final RunPersistenceRepository runPersistenceRepository;
    private final ConversationRunLinkRepository conversationRunLinkRepository;
    private final PlatformAuditEventRepository platformAuditEventRepository;
    private final PlatformMetricsService platformMetricsService;
    private final com.lx.governance.agentplatform.runtime.PromptRegistry promptRegistry;
    private final DoctorAgentPlatformProperties platformProperties;

    public DoctorPlatformRunPersistenceService(
            AgentSessionPlatformSnapshotMapper snapshotMapper,
            RunPersistenceRepository runPersistenceRepository,
            ConversationRunLinkRepository conversationRunLinkRepository,
            PlatformAuditEventRepository platformAuditEventRepository,
            PlatformMetricsService platformMetricsService,
            com.lx.governance.agentplatform.runtime.PromptRegistry promptRegistry,
            DoctorAgentPlatformProperties platformProperties) {
        this.snapshotMapper = snapshotMapper;
        this.runPersistenceRepository = runPersistenceRepository;
        this.conversationRunLinkRepository = conversationRunLinkRepository;
        this.platformAuditEventRepository = platformAuditEventRepository;
        this.platformMetricsService = platformMetricsService;
        this.promptRegistry = promptRegistry;
        this.platformProperties = platformProperties;
    }

    public RunPersistenceSnapshot persistPatientTaskRun(
            AgentSession session,
            List<PendingTask> pendingTasks,
            String conversationId,
            PlatformOutcomeMode outcomeMode,
            String triggerSource,
            String fallbackReason) {
        if (session == null) {
            return null;
        }
        RunPersistenceSnapshot snapshot = snapshotMapper.toSnapshot(
                session,
                pendingTasks,
                resolveWorkflowId(),
                resolveAgentId());
        runPersistenceRepository.save(snapshot);
        if (conversationId != null && !conversationId.isBlank()) {
            conversationRunLinkRepository.save(ConversationRunLink.builder()
                    .conversationId(conversationId)
                    .sessionId(session.getId())
                    .runId(session.getId())
                    .runType(snapshot.getRun().getRunType())
                    .entryMode(PlatformEntryMode.AGENT_RUN)
                    .outcomeMode(outcomeMode)
                    .triggerSource(triggerSource)
                    .fallbackReason(fallbackReason)
                    .linkedAt(resolveLinkedAt(session))
                    .build());
        }
        publishAuditEvent(snapshot.getRun().getRunId(), conversationId, PlatformAuditEventType.RUN_PERSISTED,
                PlatformAuditOutputScope.INTERNAL_ONLY,
                payloadOf(
                        "outcomeMode", outcomeMode != null ? outcomeMode.name() : null,
                        "triggerSource", triggerSource,
                        "fallbackReason", fallbackReason));
        publishMetrics(snapshot, pendingTasks, fallbackReason, session);
        return snapshot;
    }

    public void syncApprovalStatus(PendingTask pendingTask) {
        if (pendingTask == null || pendingTask.getAgentSessionId() == null || pendingTask.getId() == null) {
            return;
        }
        RunPersistenceSnapshot snapshot = runPersistenceRepository.findSnapshotByRunId(pendingTask.getAgentSessionId())
                .orElse(null);
        if (snapshot == null || snapshot.getRun() == null) {
            return;
        }

        List<ApprovalTask> existingTasks = snapshot.getApprovalTasks() != null
                ? snapshot.getApprovalTasks()
                : List.of();
        List<ApprovalTask> updatedTasks = new ArrayList<>();
        boolean replaced = false;
        for (ApprovalTask approvalTask : existingTasks) {
            if (approvalTask != null && String.valueOf(pendingTask.getId()).equals(approvalTask.getApprovalTaskId())) {
                updatedTasks.add(approvalTask.toBuilder()
                        .status(toApprovalStatus(pendingTask.getStatus()))
                        .build());
                replaced = true;
            } else {
                updatedTasks.add(approvalTask);
            }
        }
        if (!replaced) {
            updatedTasks.add(ApprovalTask.builder()
                    .approvalTaskId(String.valueOf(pendingTask.getId()))
                    .runId(pendingTask.getAgentSessionId())
                    .taskKey(pendingTask.getToolName())
                    .taskName(pendingTask.getToolDescription())
                    .reason(pendingTask.getReason())
                    .assigneeId(pendingTask.getDoctorId())
                    .status(toApprovalStatus(pendingTask.getStatus()))
                    .createdAt(pendingTask.getCreatedAt() != null
                            ? pendingTask.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            : null)
                    .expireAt(pendingTask.getExpireAt() != null
                            ? pendingTask.getExpireAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            : null)
                    .build());
        }

        RunPersistenceSnapshot.RunPersistenceSnapshotBuilder builder = snapshot.toBuilder();
        builder.clearApprovalTasks();
        updatedTasks.forEach(builder::approvalTask);
        runPersistenceRepository.save(builder.build());
        publishAuditEvent(
                pendingTask.getAgentSessionId(),
                snapshot.getRun() != null ? snapshot.getRun().getConversationId() : null,
                PlatformAuditEventType.APPROVAL_STATUS_SYNCED,
                PlatformAuditOutputScope.INTERNAL_ONLY,
                payloadOf(
                        "approvalTaskId", String.valueOf(pendingTask.getId()),
                        "approvalStatus", pendingTask.getStatus(),
                        "reviewComment", pendingTask.getReviewComment()));
    }

    private Long resolveLinkedAt(AgentSession session) {
        if (session.getUpdatedAt() != null) {
            return session.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (session.getCreatedAt() != null) {
            return session.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return System.currentTimeMillis();
    }

    private ApprovalStatus toApprovalStatus(String status) {
        if (status == null || status.isBlank()) {
            return ApprovalStatus.PENDING;
        }
        try {
            return ApprovalStatus.valueOf(status);
        } catch (IllegalArgumentException ignore) {
            return ApprovalStatus.PENDING;
        }
    }

    private void publishAuditEvent(
            String runId,
            String conversationId,
            PlatformAuditEventType eventType,
            PlatformAuditOutputScope outputScope,
            java.util.Map<String, Object> payload) {
        if (platformAuditEventRepository == null || runId == null) {
            return;
        }
        platformAuditEventRepository.save(PlatformAuditEvent.builder()
                .auditEventId(runId + ":" + eventType.name() + ":" + System.currentTimeMillis())
                .eventType(eventType)
                .runId(runId)
                .conversationId(conversationId)
                .agentId(resolveAgentId())
                .modelVersion(null)
                .promptVersion(resolvePromptVersion())
                .toolVersion(resolveToolVersion())
                .outputScope(outputScope)
                .payload(filterNullValues(payload))
                .createdAt(System.currentTimeMillis())
                .build());
    }

    private void publishMetrics(
            RunPersistenceSnapshot snapshot,
            List<PendingTask> pendingTasks,
            String fallbackReason,
            AgentSession session) {
        if (platformMetricsService == null || snapshot == null || snapshot.getRun() == null) {
            return;
        }
        boolean requiresApproval = pendingTasks != null
                && pendingTasks.stream().anyMatch(task -> task != null && task.getId() != null);
        boolean degraded = fallbackReason != null && !fallbackReason.isBlank();
        platformMetricsService.recordRunLifecycle(
                snapshot.getRun().getAgentId(),
                snapshot.getRun().getRunType(),
                snapshot.getRun().getStatus(),
                degraded,
                requiresApproval,
                fallbackReason,
                resolveDurationMs(session));
    }

    private long resolveDurationMs(AgentSession session) {
        if (session == null || session.getCreatedAt() == null) {
            return 0L;
        }
        java.time.LocalDateTime end = session.getCompletedAt() != null
                ? session.getCompletedAt()
                : session.getUpdatedAt() != null ? session.getUpdatedAt() : session.getCreatedAt();
        return java.time.Duration.between(session.getCreatedAt(), end).toMillis();
    }

    private java.util.Map<String, Object> filterNullValues(java.util.Map<String, Object> payload) {
        java.util.Map<String, Object> filtered = new java.util.LinkedHashMap<>();
        if (payload == null) {
            return filtered;
        }
        payload.forEach((key, value) -> {
            if (key != null && value != null) {
                filtered.put(key, value);
            }
        });
        return filtered;
    }

    private java.util.Map<String, Object> payloadOf(Object... keyValues) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null && value != null) {
                payload.put(String.valueOf(key), value);
            }
        }
        return payload;
    }

    private String resolvePromptVersion() {
        if (promptRegistry == null) {
            return ReActPromptBuilder.DEFAULT_PROMPT_VERSION;
        }
        return promptRegistry.findActiveVersion(
                ReActPromptBuilder.DOCTOR_SCENE,
                ReActPromptBuilder.DOCTOR_AGENT,
                com.lx.governance.agentplatform.model.PromptTemplateType.SYSTEM)
                .orElse(ReActPromptBuilder.DEFAULT_PROMPT_VERSION);
    }

    private String resolveWorkflowId() {
        return platformProperties != null && platformProperties.getWorkflowId() != null
                ? platformProperties.getWorkflowId()
                : "doctor-patient-task-v1";
    }

    private String resolveAgentId() {
        return platformProperties != null && platformProperties.getPlatformAgentId() != null
                ? platformProperties.getPlatformAgentId()
                : "doctor-assistant-agent";
    }

    private String resolveToolVersion() {
        return platformProperties != null && platformProperties.getToolVersion() != null
                ? platformProperties.getToolVersion()
                : "doctor-toolkit-v1";
    }
}
