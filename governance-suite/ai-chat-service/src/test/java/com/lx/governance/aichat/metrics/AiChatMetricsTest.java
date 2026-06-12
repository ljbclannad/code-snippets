package com.lx.governance.aichat.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

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
}
