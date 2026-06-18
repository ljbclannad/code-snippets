package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台治理层对入口流量做放行/灰度/熔断时的上下文。
 * <p>
 * 这些字段决定是否允许某个任务流量进入 agent 链路。
 */
@Value
@Builder(toBuilder = true)
public class PlatformDispatchContext {

    /** 任务类型。 */
    String taskType;

    /** 灰度分组。 */
    String groupId;

    /** 用户标识。 */
    String userId;

    /** 租户标识。 */
    String tenantId;

    /** 目标 agent 版本。 */
    String agentVersion;
}
