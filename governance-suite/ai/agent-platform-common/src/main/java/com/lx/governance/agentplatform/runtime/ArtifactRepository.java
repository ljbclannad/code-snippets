package com.lx.governance.agentplatform.runtime;

import java.util.List;

import com.lx.governance.agentplatform.model.ArtifactRecord;

/**
 * 平台运行产物仓储抽象。
 */
public interface ArtifactRepository {

    ArtifactRecord save(ArtifactRecord artifactRecord);

    List<ArtifactRecord> findArtifactsByRunId(String runId);
}
