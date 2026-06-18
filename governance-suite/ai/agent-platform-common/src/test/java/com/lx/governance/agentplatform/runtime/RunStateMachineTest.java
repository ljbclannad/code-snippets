package com.lx.governance.agentplatform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lx.governance.agentplatform.model.RunEventType;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.model.RunTransition;
import com.lx.governance.agentplatform.model.RunTransitionTrigger;
import org.junit.jupiter.api.Test;

class RunStateMachineTest {

    private final RunStateMachine stateMachine = new RunStateMachine();

    @Test
    void shouldSupportApprovalResumeAndRetryTransitions() {
        assertTrue(stateMachine.canTransition(RunStatus.RUNNING, RunTransitionTrigger.WAIT_FOR_APPROVAL));
        assertTrue(stateMachine.canTransition(RunStatus.WAITING_APPROVAL, RunTransitionTrigger.APPROVAL_APPROVED_RESUME));
        assertTrue(stateMachine.canTransition(RunStatus.WAITING_APPROVAL, RunTransitionTrigger.APPROVAL_REJECTED_RESUME));
        assertTrue(stateMachine.canTransition(RunStatus.FAILED, RunTransitionTrigger.RETRY));
        assertTrue(stateMachine.canTransition(RunStatus.DEGRADED, RunTransitionTrigger.RETRY));
        assertFalse(stateMachine.canTransition(RunStatus.COMPLETED, RunTransitionTrigger.RETRY));
    }

    @Test
    void shouldBuildStandardTransitionForFallbackToDirectChat() {
        RunTransition transition = stateMachine.transition(
            "run-1",
            RunStatus.RUNNING,
            RunTransitionTrigger.FALLBACK_TO_DIRECT_CHAT,
            "agent_error");

        assertEquals(RunStatus.DEGRADED, transition.getToStatus());
        assertEquals(RunEventType.RUN_DEGRADED, transition.getEventType());
        assertEquals("agent_error", transition.getReason());
    }

    @Test
    void shouldRejectUnsupportedTransition() {
        assertThrows(IllegalStateException.class,
            () -> stateMachine.nextStatus(RunStatus.WAITING_APPROVAL, RunTransitionTrigger.COMPLETE));
    }
}
