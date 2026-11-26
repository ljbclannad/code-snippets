package com.health.agent.service.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentSession;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.SessionStatus;
import com.health.agent.model.Step;
import com.lx.governance.agentplatform.model.ApprovalTask;
import com.lx.governance.agentplatform.model.Run;
import com.lx.governance.agentplatform.model.RunPersistenceSnapshot;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.runtime.RunPersistenceRepository;

/**
 * 优先从平台 run 快照恢复 doctor 审批执行所需状态。
 */
@Service
public class DoctorPlatformResumeStateService {

    private final RunPersistenceRepository runPersistenceRepository;

    public DoctorPlatformResumeStateService(RunPersistenceRepository runPersistenceRepository) {
        this.runPersistenceRepository = runPersistenceRepository;
    }

    public Optional<PlatformResumeState> loadResumeState(String sessionId, String approvalTaskId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Optional<RunPersistenceSnapshot> snapshotOptional = runPersistenceRepository.findSnapshotByRunId(sessionId);
        if (snapshotOptional.isEmpty()) {
            return Optional.empty();
        }
        RunPersistenceSnapshot snapshot = snapshotOptional.get();
        Run run = snapshot.getRun();
        if (run == null || run.getStatus() != RunStatus.WAITING_APPROVAL) {
            return Optional.empty();
        }

        ApprovalTask approvalTask = snapshot.getApprovalTasks() == null
                ? null
                : snapshot.getApprovalTasks().stream()
                        .filter(task -> task != null && approvalTaskId != null && approvalTaskId.equals(task.getApprovalTaskId()))
                        .findFirst()
                        .orElse(null);

        return Optional.of(PlatformResumeState.builder()
                .session(toSession(snapshot))
                .pendingTask(toPendingTask(approvalTask))
                .history(parseSteps(run))
                .messages(parseMessages(run))
                .context(parseContext(run))
                .currentStep(readCurrentStep(run))
                .build());
    }

    private AgentSession toSession(RunPersistenceSnapshot snapshot) {
        Run run = snapshot.getRun();
        return AgentSession.builder()
                .id(run.getRunId())
                .customerId(stringInput(run, "customerId"))
                .doctorId(stringInput(run, "doctorId"))
                .goal(stringInput(run, "goal"))
                .status(SessionStatus.PAUSED.name())
                .historyJson(stringInput(run, "historyJson"))
                .messagesJson(stringInput(run, "messagesJson"))
                .contextJson(stringInput(run, "contextJson"))
                .currentStep(readCurrentStep(run))
                .finalAnswer(stringOutput(run, "finalAnswer"))
                .errorMessage(stringOutput(run, "errorMessage"))
                .build();
    }

    private PendingTask toPendingTask(ApprovalTask approvalTask) {
        if (approvalTask == null) {
            return null;
        }
        return PendingTask.builder()
                .id(parseLong(approvalTask.getApprovalTaskId()))
                .agentSessionId(approvalTask.getRunId())
                .doctorId(approvalTask.getAssigneeId())
                .toolName(approvalTask.getTaskKey())
                .toolDescription(approvalTask.getTaskName())
                .reason(approvalTask.getReason())
                .status(approvalTask.getStatus() != null ? approvalTask.getStatus().name() : null)
                .build();
    }

    private List<Step> parseSteps(Run run) {
        return JSON.parseArray(stringInput(run, "historyJson"), Step.class);
    }

    private List<Message> parseMessages(Run run) {
        return JSON.parseArray(stringInput(run, "messagesJson"), Message.class);
    }

    private AgentContext parseContext(Run run) {
        String contextJson = stringInput(run, "contextJson");
        if (contextJson == null || contextJson.isBlank()) {
            return AgentContext.initialize(stringInput(run, "customerId"), stringInput(run, "doctorId"), stringInput(run, "goal"));
        }
        AgentContext context = JSON.parseObject(contextJson, AgentContext.class);
        return context != null ? context
                : AgentContext.initialize(stringInput(run, "customerId"), stringInput(run, "doctorId"), stringInput(run, "goal"));
    }

    private int readCurrentStep(Run run) {
        Object value = run.getInput() != null ? run.getInput().get("currentStep") : null;
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignore) {
                return 0;
            }
        }
        return 0;
    }

    private String stringInput(Run run, String key) {
        return readString(run != null ? run.getInput() : null, key);
    }

    private String stringOutput(Run run, String key) {
        return readString(run != null ? run.getOutput() : null, key);
    }

    private String readString(Map<String, Object> values, String key) {
        if (values == null || key == null) {
            return null;
        }
        Object value = values.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    @lombok.Builder
    @lombok.Value
    public static class PlatformResumeState {
        AgentSession session;
        PendingTask pendingTask;
        List<Step> history;
        List<Message> messages;
        AgentContext context;
        int currentStep;
    }
}
