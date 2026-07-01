package com.lx.governance.agentplatform.runtime;

import java.util.Optional;

import com.lx.governance.agentplatform.model.Run;

/**
 * 平台统一 run 主记录仓储。
 */
public interface RunRepository {

    Run save(Run run);

    Optional<Run> findRunById(String runId);
}
