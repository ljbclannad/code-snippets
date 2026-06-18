package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.PlatformRoutingContext;
import com.lx.governance.agentplatform.model.PlatformTaskRoutingDecision;

/**
 * 平台统一入口路由器。
 */
public interface PlatformTaskRouter {

    PlatformTaskRoutingDecision route(PlatformRoutingContext context);
}
