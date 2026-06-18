package com.lx.governance.agentplatform.model;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一工具异常输出。
 * <p>
 * 统一封装可重试性和调试信息，供平台决定是否降级。
 */
@Value
@Builder(toBuilder = true)
public class PlatformToolError {

    /** 错误码。 */
    String code;

    /** 错误信息。 */
    String message;

    /** 是否可重试。 */
    @Builder.Default
    boolean retriable = false;

    /** 附加详情。 */
    @Builder.Default
    Map<String, Object> details = new LinkedHashMap<>();
}
