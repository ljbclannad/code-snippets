package com.lx.governance.agentplatform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlatformRouteRuntimeTest {

    @Test
    void shouldShortCircuitFreshPendingReviewInvocation() {
        PlatformRouteRuntime runtime = new PlatformRouteRuntime(true, 10 * 60 * 1000L);

        PlatformRouteDecision decision = runtime.evaluateInvocation(
                PlatformPendingReviewSnapshot.builder()
                        .reviewStatus("PENDING_APPROVAL")
                        .lastOutcomeAt(System.currentTimeMillis() - 60_000)
                        .build(),
                true,
                "gray_enabled");

        assertEquals(PlatformRouteTarget.PENDING_REVIEW_SHORT_CIRCUIT, decision.target());
        assertFalse(decision.invokeAgent());
    }

    @Test
    void shouldFallbackWhenAgentResponseIsNotConsumable() {
        PlatformRouteRuntime runtime = new PlatformRouteRuntime(true, 10 * 60 * 1000L);

        PlatformRouteDecision skippedDecision = runtime.evaluateAgentResponse(PlatformAgentResponse.builder()
                .skipped(true)
                .userVisibleReply(null)
                .build());
        PlatformRouteDecision emptyReplyDecision = runtime.evaluateAgentResponse(PlatformAgentResponse.builder()
                .userVisibleReply(" ")
                .build());

        assertEquals(PlatformRouteTarget.DIRECT_CHAT_FALLBACK, skippedDecision.target());
        assertEquals("agent_skipped", skippedDecision.reason());
        assertEquals("agent_empty_reply", emptyReplyDecision.reason());
    }

    @Test
    void shouldReturnTerminalErrorWhenAutoFallbackDisabled() {
        PlatformRouteRuntime runtime = new PlatformRouteRuntime(false, 10 * 60 * 1000L);

        PlatformRouteDecision decision = runtime.fallbackDecision("agent_exception");

        assertEquals(PlatformRouteTarget.TERMINAL_ERROR, decision.target());
        assertFalse(decision.invokeAgent());
    }

    @Test
    void shouldAllowInvocationAndConsumableAgentResult() {
        PlatformRouteRuntime runtime = new PlatformRouteRuntime(true, 10 * 60 * 1000L);

        PlatformRouteDecision invocationDecision = runtime.evaluateInvocation(null, true, "gray_enabled");
        PlatformRouteDecision responseDecision = runtime.evaluateAgentResponse(PlatformAgentResponse.builder()
                .userVisibleReply("可以继续观察")
                .build());

        assertEquals(PlatformRouteTarget.AGENT, invocationDecision.target());
        assertTrue(invocationDecision.invokeAgent());
        assertEquals(PlatformRouteTarget.AGENT, responseDecision.target());
        assertTrue(responseDecision.invokeAgent());
    }
}
