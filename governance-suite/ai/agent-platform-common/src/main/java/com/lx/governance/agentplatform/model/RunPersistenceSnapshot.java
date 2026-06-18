package com.lx.governance.agentplatform.model;

import java.util.List;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台运行态持久化快照。
 * 用于承载 run 主记录及其审批、产物和遗留表映射关系。
 */
@Value
@Builder(toBuilder = true)
public class RunPersistenceSnapshot {

    Run run;

    @Singular("approvalTask")
    List<ApprovalTask> approvalTasks;

    @Singular("artifact")
    List<ArtifactRecord> artifacts;

    String legacyRunTable;

    String legacyRunId;

    String legacyApprovalTable;

    @Singular("legacyApprovalId")
    List<String> legacyApprovalIds;
}
