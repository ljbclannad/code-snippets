package com.lx.governance.agentplatform.runtime;

import java.util.List;

import com.lx.governance.agentplatform.model.RunEvent;

/**
 * 平台运行事件仓储抽象。
 */
public interface RunEventRepository {

    RunEvent save(RunEvent runEvent);

    List<RunEvent> findEventsByRunId(String runId);
}
