package com.health.agent.service;

import com.health.agent.model.AgentSession;
import com.health.agent.model.PendingTask;
import com.health.agent.model.SessionStatus;
import com.health.agent.model.TaskStatus;
import com.lx.governance.agentplatform.model.ApprovalStatus;
import com.lx.governance.agentplatform.model.ApprovalTask;
import com.lx.governance.agentplatform.model.Run;
import com.lx.governance.agentplatform.model.RunEvent;
import com.lx.governance.agentplatform.model.RunEventType;
import com.lx.governance.agentplatform.model.RunPersistenceSnapshot;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.model.RunStep;
import com.lx.governance.agentplatform.model.RunType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 将 doctor 场景遗留表投影为平台统一 run 持久化快照。
 */
@Component
public class AgentSessionPlatformSnapshotMapper {

    public RunPersistenceSnapshot toSnapshot(
        AgentSession session,
        List<PendingTask> pendingTasks,
        String workflowId,
        String agentId
    ) {
        Objects.requireNonNull(session, "session must not be null");
        List<PendingTask> safePendingTasks = pendingTasks != null ? pendingTasks : List.of();
        RunStatus runStatus = toRunStatus(session.getStatus(), safePendingTasks);

        Run.RunBuilder runBuilder = Run.builder()
            .runId(session.getId())
            .sessionId(session.getId())
            .workflowId(workflowId)
            .agentId(agentId)
            .runType(RunType.TASK)
            .status(runStatus)
            .input(buildInput(session))
            .output(buildOutput(session))
            .createdAt(toEpochMillis(session.getCreatedAt()))
            .startedAt(toEpochMillis(session.getCreatedAt()))
            .endedAt(resolveEndedAt(session, runStatus));

        RunStep currentStep = buildCurrentStep(session, runStatus, agentId);
        if (currentStep != null) {
            runBuilder.step(currentStep);
        }
        buildEvents(session, safePendingTasks, runStatus).forEach(runBuilder::event);

        RunPersistenceSnapshot.RunPersistenceSnapshotBuilder snapshotBuilder = RunPersistenceSnapshot.builder()
            .run(runBuilder.build())
            .legacyRunTable("agent_session")
            .legacyRunId(session.getId())
            .legacyApprovalTable("agent_pending_task");

        for (PendingTask pendingTask : safePendingTasks) {
            snapshotBuilder.approvalTask(toApprovalTask(pendingTask));
            if (pendingTask.getId() != null) {
                snapshotBuilder.legacyApprovalId(String.valueOf(pendingTask.getId()));
            }
        }

        return snapshotBuilder.build();
    }

    private Map<String, Object> buildInput(AgentSession session) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("customerId", session.getCustomerId());
        input.put("doctorId", session.getDoctorId());
        input.put("goal", session.getGoal());
        input.put("historyJson", session.getHistoryJson());
        input.put("messagesJson", session.getMessagesJson());
        input.put("contextJson", session.getContextJson());
        input.put("currentStep", session.getCurrentStep());
        return input;
    }

    private Map<String, Object> buildOutput(AgentSession session) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("finalAnswer", session.getFinalAnswer());
        output.put("errorMessage", session.getErrorMessage());
        return output;
    }

    private RunStep buildCurrentStep(AgentSession session, RunStatus runStatus, String agentId) {
        if (session.getCurrentStep() == null) {
            return null;
        }
        return RunStep.builder()
            .stepIndex(session.getCurrentStep())
            .stepId(session.getId() + ":step:" + session.getCurrentStep())
            .nodeType("react_loop")
            .nodeName("doctor-session-loop")
            .agentId(agentId)
            .status(runStatus)
            .input(Map.of("goal", defaultString(session.getGoal())))
            .output(buildOutput(session))
            .errorMessage(session.getErrorMessage())
            .startedAt(toEpochMillis(session.getCreatedAt()))
            .endedAt(resolveEndedAt(session, runStatus))
            .build();
    }

    private List<RunEvent> buildEvents(AgentSession session, List<PendingTask> pendingTasks, RunStatus runStatus) {
        List<RunEvent> events = new ArrayList<>();
        Long createdAt = toEpochMillis(session.getCreatedAt());
        Long updatedAt = toEpochMillis(session.getUpdatedAt());
        Long completedAt = toEpochMillis(session.getCompletedAt());

        if (createdAt != null) {
            events.add(buildEvent(session.getId(), null, RunEventType.RUN_CREATED, "legacy session created", createdAt,
                payloadOf(
                    "customerId", session.getCustomerId(),
                    "doctorId", session.getDoctorId(),
                    "goal", session.getGoal())));
            events.add(buildEvent(session.getId(), null, RunEventType.RUN_STARTED, "legacy session started", createdAt,
                payloadOf("currentStep", session.getCurrentStep())));
        }
        if (runStatus == RunStatus.WAITING_APPROVAL) {
            Long suspendedAt = pendingTasks.stream()
                .map(PendingTask::getCreatedAt)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .map(this::toEpochMillis)
                .orElse(updatedAt != null ? updatedAt : createdAt);
            events.add(buildEvent(session.getId(), null, RunEventType.WAITING_APPROVAL, "waiting approval", suspendedAt,
                payloadOf("pendingTasks", summarizePendingTasks(pendingTasks))));
        }
        if (runStatus == RunStatus.COMPLETED) {
            events.add(buildEvent(session.getId(), null, RunEventType.RUN_COMPLETED, "legacy session completed",
                completedAt != null ? completedAt : updatedAt,
                payloadOf("finalAnswer", session.getFinalAnswer())));
        } else if (runStatus == RunStatus.FAILED) {
            events.add(buildEvent(session.getId(), null, RunEventType.RUN_FAILED, "legacy session failed",
                completedAt != null ? completedAt : updatedAt,
                payloadOf("errorMessage", session.getErrorMessage())));
        }
        return events;
    }

    private RunEvent buildEvent(
            String runId,
            String stepId,
            RunEventType eventType,
            String message,
            Long createdAt,
            Map<String, Object> payload) {
        return RunEvent.builder()
            .eventId(runId + ":" + eventType.name() + ":" + defaultLong(createdAt))
            .runId(runId)
            .stepId(stepId)
            .eventType(eventType)
            .message(message)
            .payload(payload)
            .createdAt(createdAt)
            .build();
    }

    private List<Map<String, Object>> summarizePendingTasks(List<PendingTask> pendingTasks) {
        return pendingTasks.stream()
            .filter(Objects::nonNull)
            .map(task -> {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("taskId", task.getId());
                summary.put("toolName", task.getToolName());
                summary.put("toolDescription", task.getToolDescription());
                summary.put("reason", task.getReason());
                summary.put("status", task.getStatus());
                return summary;
            })
            .collect(Collectors.toList());
    }

    private Map<String, Object> payloadOf(Object... keyValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null && value != null) {
                payload.put(String.valueOf(key), value);
            }
        }
        return payload;
    }

    private ApprovalTask toApprovalTask(PendingTask pendingTask) {
        return ApprovalTask.builder()
            .approvalTaskId(pendingTask.getId() != null ? String.valueOf(pendingTask.getId()) : null)
            .runId(pendingTask.getAgentSessionId())
            .taskKey(pendingTask.getToolName())
            .taskName(pendingTask.getToolDescription())
            .reason(pendingTask.getReason())
            .assigneeId(pendingTask.getDoctorId())
            .status(toApprovalStatus(pendingTask.getStatus()))
            .createdAt(toEpochMillis(pendingTask.getCreatedAt()))
            .expireAt(toEpochMillis(pendingTask.getExpireAt()))
            .build();
    }

    private RunStatus toRunStatus(String sessionStatus, List<PendingTask> pendingTasks) {
        if (pendingTasks.stream().anyMatch(this::isPendingApproval)) {
            return RunStatus.WAITING_APPROVAL;
        }
        if (sessionStatus == null || sessionStatus.isBlank()) {
            return RunStatus.PENDING;
        }
        try {
            return switch (SessionStatus.valueOf(sessionStatus)) {
                case RUNNING -> RunStatus.RUNNING;
                case PAUSED -> RunStatus.WAITING_APPROVAL;
                case COMPLETED -> RunStatus.COMPLETED;
                case FAILED -> RunStatus.FAILED;
            };
        } catch (IllegalArgumentException ignore) {
            return RunStatus.PENDING;
        }
    }

    private boolean isPendingApproval(PendingTask pendingTask) {
        return pendingTask != null && TaskStatus.PENDING.name().equalsIgnoreCase(pendingTask.getStatus());
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

    private Long resolveEndedAt(AgentSession session, RunStatus runStatus) {
        if (runStatus == RunStatus.COMPLETED || runStatus == RunStatus.FAILED) {
            Long completedAt = toEpochMillis(session.getCompletedAt());
            return completedAt != null ? completedAt : toEpochMillis(session.getUpdatedAt());
        }
        return null;
    }

    private Long toEpochMillis(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String defaultString(String value) {
        return value != null ? value : "";
    }

    private long defaultLong(Long value) {
        return value != null ? value : 0L;
    }
}
