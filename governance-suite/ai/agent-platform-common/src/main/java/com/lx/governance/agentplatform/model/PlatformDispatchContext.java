package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台治理层对入口流量做放行/灰度/熔断时的上下文。
 */
@Value
@Builder(toBuilder = true)
public class PlatformDispatchContext {

    String taskType;

    String groupId;

    String userId;

    String tenantId;

    String agentVersion;
}
