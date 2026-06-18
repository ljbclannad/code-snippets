package com.lx.governance.agentplatform.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

/**
 * 平台级工具重试策略。
 */
@Value
@Builder
@AllArgsConstructor
public class ToolRetryPolicy {

    int maxAttempts;

    long backoffMs;

    boolean retryOnTimeout;

    boolean retryOnThrottled;

    public static ToolRetryPolicy noRetry() {
        return ToolRetryPolicy.builder()
                .maxAttempts(1)
                .backoffMs(0L)
                .retryOnTimeout(false)
                .retryOnThrottled(false)
                .build();
    }
}
