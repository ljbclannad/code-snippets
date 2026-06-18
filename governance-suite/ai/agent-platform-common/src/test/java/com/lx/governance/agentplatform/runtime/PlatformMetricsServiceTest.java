package com.lx.governance.agentplatform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.lx.governance.agentplatform.model.PlatformCostScope;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.model.RunType;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PlatformMetricsServiceTest {

    @Test
    void shouldRecordRunLifecycleMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformMetricsService service = new PlatformMetricsService(registry);

        service.recordRunLifecycle(
                "doctor-assistant-agent",
                RunType.TASK,
                RunStatus.COMPLETED,
                false,
                true,
                null,
                500L);

        assertEquals(1.0, registry.get("platform.run.total")
                .tag("agent_id", "doctor-assistant-agent")
                .tag("run_type", "TASK")
                .counter().count());
        assertEquals(1.0, registry.get("platform.run.outcome")
                .tag("outcome", "success")
                .counter().count());
        assertEquals(1.0, registry.get("platform.run.approval.required")
                .tag("agent_id", "doctor-assistant-agent")
                .counter().count());
        assertEquals(1L, registry.get("platform.run.latency").timer().count());
    }

    @Test
    void shouldRecordFallbackAndTokenCostMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformMetricsService service = new PlatformMetricsService(registry);

        service.recordRunLifecycle(
                "doctor-assistant-agent",
                RunType.TASK,
                RunStatus.FAILED,
                true,
                false,
                "AGENT_EXCEPTION",
                200L);
        service.recordTokenCost(PlatformCostScope.AGENT_WORKFLOW, "doctor-assistant-agent", 100, 50, 150, 0.23D);

        assertEquals(1.0, registry.get("platform.run.fallback")
                .tag("reason", "AGENT_EXCEPTION")
                .counter().count());
        assertEquals(150.0, registry.get("platform.tokens.total")
                .tag("scope", "AGENT_WORKFLOW")
                .summary().totalAmount());
        assertEquals(0.23D, registry.get("platform.cost.estimated")
                .tag("scope", "AGENT_WORKFLOW")
                .summary().totalAmount());
    }
}
