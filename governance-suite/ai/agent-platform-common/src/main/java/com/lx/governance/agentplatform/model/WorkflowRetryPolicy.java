package com.lx.governance.agentplatform.model;

import java.util.List;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 节点重试策略。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowRetryPolicy {

    Integer maxAttempts;

    Long initialBackoffMs;

    Double backoffMultiplier;

    @Singular("retryableError")
    List<String> retryableErrors;
}
