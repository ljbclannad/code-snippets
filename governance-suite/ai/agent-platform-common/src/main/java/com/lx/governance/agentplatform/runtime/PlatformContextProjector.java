package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.PlatformContext;
import com.lx.governance.agentplatform.model.PlatformContextBudget;
import com.lx.governance.agentplatform.model.PlatformContextChannel;

/**
 * 平台上下文投影器。
 * <p>
 * 负责把完整上下文按通道和预算裁成目标场景可消费的子集。
 */
public interface PlatformContextProjector {

    PlatformContext project(
            PlatformContext source,
            PlatformContextChannel channel,
            PlatformContextBudget budget);
}
