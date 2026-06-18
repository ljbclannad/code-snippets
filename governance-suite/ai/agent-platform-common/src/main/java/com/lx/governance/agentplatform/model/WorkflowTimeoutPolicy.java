package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 节点或流程级超时策略。
 * <p>
 * 定义超时后要跳转到哪个节点，以及最终应该落到什么状态。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowTimeoutPolicy {

    /** 超时时间。 */
    Long timeoutMs;

    /** 超时跳转目标节点。 */
    String timeoutTargetNodeId;

    /** 超时后的 run 状态。 */
    RunStatus timeoutRunStatus;
}
