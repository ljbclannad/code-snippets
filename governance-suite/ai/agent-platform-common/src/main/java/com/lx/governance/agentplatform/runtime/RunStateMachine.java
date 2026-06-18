package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.RunEventType;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.model.RunTransition;
import com.lx.governance.agentplatform.model.RunTransitionTrigger;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 平台统一 run 状态机。
 */
public class RunStateMachine {

    private final Map<RunStatus, Map<RunTransitionTrigger, RunStatus>> transitions;

    public RunStateMachine() {
        this.transitions = buildTransitions();
    }

    public boolean canTransition(RunStatus fromStatus, RunTransitionTrigger trigger) {
        if (fromStatus == null || trigger == null) {
            return false;
        }
        return transitions.getOrDefault(fromStatus, Map.of()).containsKey(trigger);
    }

    public RunStatus nextStatus(RunStatus fromStatus, RunTransitionTrigger trigger) {
        if (!canTransition(fromStatus, trigger)) {
            throw new IllegalStateException("Unsupported run transition: " + fromStatus + " -> " + trigger);
        }
        return transitions.get(fromStatus).get(trigger);
    }

    public RunTransition transition(String runId, RunStatus fromStatus, RunTransitionTrigger trigger, String reason) {
        RunStatus toStatus = nextStatus(fromStatus, trigger);
        return RunTransition.builder()
            .runId(runId)
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .trigger(trigger)
            .eventType(toEventType(trigger, toStatus))
            .reason(reason)
            .build();
    }

    public Set<RunTransitionTrigger> supportedTriggers(RunStatus status) {
        return transitions.containsKey(status)
            ? EnumSet.copyOf(transitions.get(status).keySet())
            : EnumSet.noneOf(RunTransitionTrigger.class);
    }

    private Map<RunStatus, Map<RunTransitionTrigger, RunStatus>> buildTransitions() {
        Map<RunStatus, Map<RunTransitionTrigger, RunStatus>> matrix = new EnumMap<>(RunStatus.class);
        matrix.put(RunStatus.PENDING, mapOf(
            RunTransitionTrigger.START, RunStatus.RUNNING,
            RunTransitionTrigger.CANCEL, RunStatus.CANCELLED));
        matrix.put(RunStatus.RUNNING, mapOf(
            RunTransitionTrigger.WAIT_FOR_APPROVAL, RunStatus.WAITING_APPROVAL,
            RunTransitionTrigger.COMPLETE, RunStatus.COMPLETED,
            RunTransitionTrigger.FAIL, RunStatus.FAILED,
            RunTransitionTrigger.DEGRADE, RunStatus.DEGRADED,
            RunTransitionTrigger.FALLBACK_TO_DIRECT_CHAT, RunStatus.DEGRADED,
            RunTransitionTrigger.CANCEL, RunStatus.CANCELLED));
        matrix.put(RunStatus.WAITING_APPROVAL, mapOf(
            RunTransitionTrigger.APPROVAL_APPROVED_RESUME, RunStatus.RUNNING,
            RunTransitionTrigger.APPROVAL_REJECTED_RESUME, RunStatus.RUNNING,
            RunTransitionTrigger.CANCEL, RunStatus.CANCELLED));
        matrix.put(RunStatus.FAILED, mapOf(
            RunTransitionTrigger.RETRY, RunStatus.RUNNING,
            RunTransitionTrigger.CANCEL, RunStatus.CANCELLED));
        matrix.put(RunStatus.DEGRADED, mapOf(
            RunTransitionTrigger.RETRY, RunStatus.RUNNING,
            RunTransitionTrigger.CANCEL, RunStatus.CANCELLED));
        matrix.put(RunStatus.COMPLETED, Map.of());
        matrix.put(RunStatus.CANCELLED, Map.of());
        return matrix;
    }

    private Map<RunTransitionTrigger, RunStatus> mapOf(Object... values) {
        Map<RunTransitionTrigger, RunStatus> mapping = new EnumMap<>(RunTransitionTrigger.class);
        for (int i = 0; i < values.length; i += 2) {
            mapping.put((RunTransitionTrigger) values[i], (RunStatus) values[i + 1]);
        }
        return mapping;
    }

    private RunEventType toEventType(RunTransitionTrigger trigger, RunStatus toStatus) {
        return switch (trigger) {
            case START -> RunEventType.RUN_STARTED;
            case WAIT_FOR_APPROVAL -> RunEventType.WAITING_APPROVAL;
            case APPROVAL_APPROVED_RESUME, APPROVAL_REJECTED_RESUME -> RunEventType.APPROVAL_RESUMED;
            case COMPLETE -> RunEventType.RUN_COMPLETED;
            case FAIL -> RunEventType.RUN_FAILED;
            case DEGRADE, FALLBACK_TO_DIRECT_CHAT -> RunEventType.RUN_DEGRADED;
            case CANCEL -> RunEventType.RUN_CANCELLED;
            case RETRY -> RunEventType.RUN_STARTED;
            case CREATE -> RunEventType.RUN_CREATED;
        };
    }
}
