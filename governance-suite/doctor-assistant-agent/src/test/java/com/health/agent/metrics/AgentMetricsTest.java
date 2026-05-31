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
}
