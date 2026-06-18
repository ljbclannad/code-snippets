package com.lx.governance.agentplatform.runtime;

import lombok.Builder;
import lombok.Value;

/**
 * 平台路由运行时消费的待审批态快照。
 * <p>
 * 仅保留短路判断需要的最小信息。
 */
@Value
@Builder
public class PlatformPendingReviewSnapshot {

    /** 当前审批状态。 */
    String reviewStatus;

    /** 最近一次结果时间。 */
    Long lastOutcomeAt;
}
