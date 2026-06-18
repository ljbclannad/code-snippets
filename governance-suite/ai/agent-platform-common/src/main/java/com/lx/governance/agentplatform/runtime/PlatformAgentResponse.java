package com.lx.governance.agentplatform.runtime;

import lombok.Builder;
import lombok.Value;

/**
 * 平台路由运行时消费的 Agent 响应视图。
 * <p>
 * 只保留路由是否继续消费所需的最小字段集。
 */
@Value
@Builder
public class PlatformAgentResponse {

    /** 是否被跳过。 */
    boolean skipped;

    /** 是否发生错误。 */
    boolean error;

    /** 面向用户的回复。 */
    String userVisibleReply;
}
