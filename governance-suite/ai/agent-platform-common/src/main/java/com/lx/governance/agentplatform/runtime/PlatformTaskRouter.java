package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.PlatformRoutingContext;
import com.lx.governance.agentplatform.model.PlatformTaskRoutingDecision;

/**
 * 平台统一入口路由器。
 * <p>
 * 负责把入口请求路由到直聊、任务升级或其他平台入口。
 */
public interface PlatformTaskRouter {

    PlatformTaskRoutingDecision route(PlatformRoutingContext context);
}
