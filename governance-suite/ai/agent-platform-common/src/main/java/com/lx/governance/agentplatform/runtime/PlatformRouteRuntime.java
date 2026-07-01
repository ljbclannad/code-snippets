package com.lx.governance.agentplatform.runtime;

/**
 * 平台统一 route / fallback 运行时。
 * <p>
 * 负责把“是否短路待审批、是否允许调 agent、agent 返回后能否继续消费”这几类判断串起来。
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
        // 待审批窗口内直接短路，避免重复放行同一条链路。
        if (shouldShortCircuitPendingReview(pendingReviewSnapshot)) {
            return new PlatformRouteDecision(
                    PlatformRouteTarget.PENDING_REVIEW_SHORT_CIRCUIT,
                    false,
                    "pending_review_short_circuit");
        }
        // 治理策略未放行时，直接走直聊兜底。
        if (!dispatchAllowed) {
            return new PlatformRouteDecision(PlatformRouteTarget.DIRECT_CHAT_FALLBACK, false, dispatchReason);
        }
        // 其余情况交给 agent 执行。
        return new PlatformRouteDecision(PlatformRouteTarget.AGENT, true, dispatchReason);
    }

    public PlatformRouteDecision evaluateAgentResponse(PlatformAgentResponse agentResponse) {
        // agent 返回空值、跳过、错误或空回复时都不能直接消费。
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
        // 是否能兜底到直聊，由平台开关控制。
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
        // 在 pendingReviewWindowMillis 窗口内，保持短路，避免重复触发审批恢复。
        long elapsedMs = System.currentTimeMillis() - pendingReviewSnapshot.getLastOutcomeAt();
        return elapsedMs >= 0 && elapsedMs <= pendingReviewWindowMillis;
    }
}
