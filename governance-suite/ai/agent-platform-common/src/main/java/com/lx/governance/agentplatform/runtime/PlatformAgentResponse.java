package com.lx.governance.agentplatform.runtime;

import lombok.Builder;
import lombok.Value;

/**
 * 平台路由运行时消费的 Agent 响应视图。
 */
@Value
@Builder
public class PlatformAgentResponse {

    boolean skipped;

    boolean error;

    String userVisibleReply;
}
