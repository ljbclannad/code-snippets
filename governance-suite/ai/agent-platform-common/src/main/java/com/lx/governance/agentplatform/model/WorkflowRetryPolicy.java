package com.lx.governance.agentplatform.model;

import java.util.List;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 节点重试策略。
 * <p>
 * 用指数退避控制失败后的再次尝试节奏。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowRetryPolicy {

    /** 最大尝试次数。 */
    Integer maxAttempts;

    /** 初始退避时长。 */
    Long initialBackoffMs;

    /** 退避倍率。 */
    Double backoffMultiplier;

    /** 可重试错误列表。 */
    @Singular("retryableError")
    List<String> retryableErrors;
}
