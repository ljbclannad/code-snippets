package com.lx.governance.agentplatform.runtime;

import java.util.Optional;

import com.lx.governance.agentplatform.model.RunPersistenceSnapshot;

/**
 * 平台 run 聚合快照仓储。
 */
public interface RunPersistenceRepository {

    RunPersistenceSnapshot save(RunPersistenceSnapshot snapshot);

    Optional<RunPersistenceSnapshot> findSnapshotByRunId(String runId);
}
