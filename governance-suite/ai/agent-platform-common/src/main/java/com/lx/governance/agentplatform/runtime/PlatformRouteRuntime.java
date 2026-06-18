package com.lx.governance.agentplatform.runtime;

/**
 * 平台统一 route / fallback 运行时。
 */
public class PlatformRouteRuntime {

    private static final String PENDING_APPROVAL_STATUS = "PENDING_APPROVAL";

    private final boolean autoFallbackToDirectChat;
    private final long pendingReviewWindowMillis;

    public PlatformRouteRuntime(boolean autoFallbackToDirectChat, long pendingReviewWindowMillis) {
        this.autoFallbackToDirectChat = autoFallbackToDirectChat;
        this.pendingReviewWindowMillis = pendingReviewWindowMillis;
    }

    public PlatformRouteDecision evaluateInvocation(
            PlatformPendingReviewSnapshot pendingReviewSnapshot,
            boolean dispatchAllowed,
            String dispatchReason) {
        if (shouldShortCircuitPendingReview(pendingReviewSnapshot)) {
            return new PlatformRouteDecision(
                    PlatformRouteTarget.PENDING_REVIEW_SHORT_CIRCUIT,
                    false,
                    "pending_review_short_circuit");
        }
        if (!dispatchAllowed) {
            return new PlatformRouteDecision(PlatformRouteTarget.DIRECT_CHAT_FALLBACK, false, dispatchReason);
        }
        return new PlatformRouteDecision(PlatformRouteTarget.AGENT, true, dispatchReason);
    }

    public PlatformRouteDecision evaluateAgentResponse(PlatformAgentResponse agentResponse) {
        if (agentResponse == null) {
            return fallbackDecision("agent_response_null");
        }
        if (agentResponse.isSkipped()) {
            return fallbackDecision("agent_skipped");
        }
        if (agentResponse.isError()) {
            return fallbackDecision("agent_error");
        }
        if (agentResponse.getUserVisibleReply() == null || agentResponse.getUserVisibleReply().isBlank()) {
            return fallbackDecision("agent_empty_reply");
        }
        return new PlatformRouteDecision(PlatformRouteTarget.AGENT, true, "agent_result_consumable");
    }

    public PlatformRouteDecision fallbackDecision(String reason) {
        return new PlatformRouteDecision(
                autoFallbackToDirectChat ? PlatformRouteTarget.DIRECT_CHAT_FALLBACK : PlatformRouteTarget.TERMINAL_ERROR,
                false,
                reason);
    }

    boolean shouldShortCircuitPendingReview(PlatformPendingReviewSnapshot pendingReviewSnapshot) {
        if (pendingReviewSnapshot == null) {
            return false;
        }
        if (!PENDING_APPROVAL_STATUS.equals(pendingReviewSnapshot.getReviewStatus())) {
            return false;
        }
        if (pendingReviewSnapshot.getLastOutcomeAt() == null) {
            return false;
        }
        long elapsedMs = System.currentTimeMillis() - pendingReviewSnapshot.getLastOutcomeAt();
        return elapsedMs >= 0 && elapsedMs <= pendingReviewWindowMillis;
    }
}
