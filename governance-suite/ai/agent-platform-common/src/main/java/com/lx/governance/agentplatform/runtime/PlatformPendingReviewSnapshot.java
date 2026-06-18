package com.lx.governance.agentplatform.runtime;

import lombok.Builder;
import lombok.Value;

/**
 * 平台路由运行时消费的待审批态快照。
 */
@Value
@Builder
public class PlatformPendingReviewSnapshot {

    String reviewStatus;

    Long lastOutcomeAt;
}
