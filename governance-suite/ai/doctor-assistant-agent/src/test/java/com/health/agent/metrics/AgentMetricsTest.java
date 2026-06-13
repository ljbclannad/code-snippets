package com.health.agent.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentMetricsTest {

    @Test
    void shouldRecordFailureDurationAndSteps() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentMetrics agentMetrics = new AgentMetrics(meterRegistry);

        agentMetrics.recordExecutionFailure("TimeoutException", 4, 250L);

        Timer failureTimer = meterRegistry.find("agent.execution.duration")
            .tag("outcome", "failure")
            .timer();
        assertNotNull(failureTimer);
        assertEquals(1, failureTimer.count());
        assertEquals(
            1,
            meterRegistry.get("agent.execution.steps")
                .summary()
                .count()
        );
        assertEquals(
            1,
            meterRegistry.get("agent.execution.failure.by_type")
                .tag("error_type", "TimeoutException")
                .counter()
                .count()
        );
    }

    @Test
    void shouldRecordApprovalDecisionMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentMetrics agentMetrics = new AgentMetrics(meterRegistry);

        agentMetrics.recordApprovalDecision("approved", "PATIENT_FOLLOW_UP_ANALYSIS");
        agentMetrics.recordApprovalDecision("rejected", "RISK_STRATIFICATION_AND_ESCALATION");

        assertEquals(
            1,
            meterRegistry.get("agent.approval.by_decision")
                .tag("decision", "approved")
                .tag("task_type", "PATIENT_FOLLOW_UP_ANALYSIS")
                .counter()
                .count()
        );
        assertEquals(
            1,
            meterRegistry.get("agent.approval.by_decision")
                .tag("decision", "rejected")
                .tag("task_type", "RISK_STRATIFICATION_AND_ESCALATION")
                .counter()
                .count()
        );
    }
}
