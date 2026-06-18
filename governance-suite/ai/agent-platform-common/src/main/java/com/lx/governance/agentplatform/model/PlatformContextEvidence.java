package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一证据记录。
 * <p>
 * 表示支撑上下文结论的一条证据或来源材料。
 */
@Value
@Builder(toBuilder = true)
public class PlatformContextEvidence {

    /** 证据键。 */
    String evidenceKey;

    /** 来源。 */
    String source;

    /** 摘要。 */
    String summary;

    /** 可见性。 */
    PlatformContextVisibility visibility;

    /** 原始载荷。 */
    Object payload;
}
