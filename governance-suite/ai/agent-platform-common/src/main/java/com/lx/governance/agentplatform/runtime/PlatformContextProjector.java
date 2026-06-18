package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.PlatformContext;
import com.lx.governance.agentplatform.model.PlatformContextBudget;
import com.lx.governance.agentplatform.model.PlatformContextChannel;

/**
 * 平台上下文投影器。
 */
public interface PlatformContextProjector {

    PlatformContext project(
            PlatformContext source,
            PlatformContextChannel channel,
            PlatformContextBudget budget);
}
