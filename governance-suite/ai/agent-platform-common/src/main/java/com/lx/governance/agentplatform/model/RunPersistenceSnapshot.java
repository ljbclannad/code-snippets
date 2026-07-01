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

    /** 主 run 数据。 */
    Run run;

    /** 关联审批任务。 */
    @Singular("approvalTask")
    List<ApprovalTask> approvalTasks;

    /** 运行期间生成的产物。 */
    @Singular("artifact")
    List<ArtifactRecord> artifacts;

    /** 遗留 run 表名。 */
    String legacyRunTable;

    /** 遗留 run 主键。 */
    String legacyRunId;

    /** 遗留审批表名。 */
    String legacyApprovalTable;

    /** 遗留审批记录 id 列表。 */
    @Singular("legacyApprovalId")
    List<String> legacyApprovalIds;
}
