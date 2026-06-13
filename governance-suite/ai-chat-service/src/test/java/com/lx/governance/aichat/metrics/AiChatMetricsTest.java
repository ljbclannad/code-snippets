package com.lx.governance.aichat.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerReason;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiChatMetricsTest {

    @Test
    void shouldRecordRewriteMetricsBySourceAndReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiChatMetrics metrics = new AiChatMetrics(registry);

        metrics.recordRewrite("RULE", "context_enriched", 12);
        metrics.recordRewrite("LLM", "complex_context_rewrite", 20);
        metrics.recordRewrite("FALLBACK_RULE", "llm_invalid_or_empty", 8);

        assertEquals(1.0, registry.get("ai.rewrite.requests")
                .tag("source", "RULE")
                .tag("reason", "context_enriched")
                .counter()
                .count());
        assertEquals(1.0, registry.get("ai.rewrite.requests")
                .tag("source", "LLM")
                .tag("reason", "complex_context_rewrite")
                .counter()
                .count());
        assertEquals(1.0, registry.get("ai.rewrite.requests")
                .tag("source", "FALLBACK_RULE")
                .tag("reason", "llm_invalid_or_empty")
                .counter()
                .count());
        assertEquals(1L, registry.get("ai.rewrite.time")
                .tag("source", "LLM")
                .timer()
                .count());
    }

    @Test
    void shouldRecordTaskTriggerMetricsByRouteTaskTypeAndReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiChatMetrics metrics = new AiChatMetrics(registry);

        TaskTriggerDecision decision = TaskTriggerDecision.builder()
                .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .reasons(java.util.List.of(
                        TaskTriggerReason.EXPLICIT_ANALYSIS_REQUEST,
                        TaskTriggerReason.TREND_ANALYSIS_REQUEST))
                .summary("命中随访趋势分析信号")
                .build();

        metrics.recordTaskTriggerDecision(decision);

        assertEquals(1.0, registry.get("ai.task.trigger.decisions")
                .tag("route", "DOCTOR_ASSISTANT_AGENT")
                .tag("task_type", "PATIENT_FOLLOW_UP_ANALYSIS")
                .counter()
                .count());
        assertEquals(1.0, registry.get("ai.task.trigger.reasons")
                .tag("route", "DOCTOR_ASSISTANT_AGENT")
                .tag("task_type", "PATIENT_FOLLOW_UP_ANALYSIS")
                .tag("reason", "EXPLICIT_ANALYSIS_REQUEST")
                .counter()
                .count());
        assertEquals(1.0, registry.get("ai.task.trigger.reasons")
                .tag("route", "DOCTOR_ASSISTANT_AGENT")
                .tag("task_type", "PATIENT_FOLLOW_UP_ANALYSIS")
                .tag("reason", "TREND_ANALYSIS_REQUEST")
                .counter()
                .count());
    }

    @Test
    void shouldRecordAgentInvocationAndSafetyMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiChatMetrics metrics = new AiChatMetrics(registry);

        metrics.recordAgentUpgradeTriggered();
        metrics.recordAgentInvocation("PATIENT_FOLLOW_UP_ANALYSIS", "success", 35);
        metrics.recordConversationPathLatency("agent", 42);
        metrics.recordPatientSafetyInterception("MANDATORY_ESCALATION");
        metrics.recordAgentFallback("agent_exception");
        metrics.recordHumanReviewRequest("RISK_STRATIFICATION_AND_ESCALATION");

        assertEquals(1.0, registry.get("ai.agent.upgrade.triggered")
                .counter()
                .count());
        assertEquals(1.0, registry.get("ai.agent.invocations")
                .tag("task_type", "PATIENT_FOLLOW_UP_ANALYSIS")
                .tag("outcome", "success")
                .counter()
                .count());
        assertEquals(1L, registry.get("ai.agent.response.time")
                .tag("outcome", "success")
                .timer()
                .count());
        assertEquals(1L, registry.get("ai.conversation.path.time")
                .tag("path", "agent")
                .timer()
                .count());
        assertEquals(1.0, registry.get("ai.patient.safety.interceptions")
                .tag("category", "MANDATORY_ESCALATION")
                .counter()
                .count());
        assertEquals(1.0, registry.get("ai.agent.fallbacks")
                .tag("reason", "agent_exception")
                .counter()
                .count());
        assertEquals(1.0, registry.get("ai.agent.human.review.requests")
                .tag("task_type", "RISK_STRATIFICATION_AND_ESCALATION")
                .counter()
                .count());
    }
}
