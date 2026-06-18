package com.lx.governance.agentplatform.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

/**
 * 平台级工具重试策略。
 * <p>
 * 控制工具调用失败后是否还要继续重试。
 */
@Value
@Builder
@AllArgsConstructor
public class ToolRetryPolicy {

    /** 最大尝试次数。 */
    int maxAttempts;

    /** 每次失败后的退避时长。 */
    long backoffMs;

    /** 超时是否重试。 */
    boolean retryOnTimeout;

    /** 被限流时是否重试。 */
    boolean retryOnThrottled;

    /** 不重试的快捷构造。 */
    public static ToolRetryPolicy noRetry() {
        return ToolRetryPolicy.builder()
                .maxAttempts(1)
                .backoffMs(0L)
                .retryOnTimeout(false)
                .retryOnThrottled(false)
                .build();
    }
}
