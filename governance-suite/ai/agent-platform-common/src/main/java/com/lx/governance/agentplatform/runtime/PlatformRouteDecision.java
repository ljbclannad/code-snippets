package com.lx.governance.agentplatform.runtime;

/**
 * 平台统一路由决策。
 * <p>
 * 这里把最终路由目标和是否真正进入 agent 执行分开表示。
 */
public record PlatformRouteDecision(PlatformRouteTarget target, boolean invokeAgent, String reason) {
}
