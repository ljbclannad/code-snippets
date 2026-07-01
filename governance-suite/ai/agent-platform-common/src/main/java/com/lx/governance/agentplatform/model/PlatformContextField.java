package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台上下文字段。
 * <p>
 * 单个字段会标注来源域和可见性，方便裁剪与脱敏。
 */
@Value
@Builder(toBuilder = true)
public class PlatformContextField {

    /** 字段键。 */
    String fieldKey;

    /** 所属领域。 */
    PlatformContextDomain domain;

    /** 可见性。 */
    PlatformContextVisibility visibility;

    /** 来源描述。 */
    String source;

    /** 字段值。 */
    Object value;
}
