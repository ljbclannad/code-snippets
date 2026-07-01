package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.LoopContext;
import com.lx.governance.agentplatform.model.LoopOutcome;

/**
 * 平台统一循环执行器接口。
 */
public interface PlatformLoopExecutor<S, R> {

    LoopOutcome<S, R> executeLoop(LoopContext<S> loopContext);
}
