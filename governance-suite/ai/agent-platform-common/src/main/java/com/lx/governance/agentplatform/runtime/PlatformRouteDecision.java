package com.lx.governance.agentplatform.runtime;

/**
 * 平台统一路由决策。
 */
public record PlatformRouteDecision(PlatformRouteTarget target, boolean invokeAgent, String reason) {
}
