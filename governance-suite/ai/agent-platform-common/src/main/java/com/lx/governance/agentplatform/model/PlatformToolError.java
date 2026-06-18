package com.lx.governance.agentplatform.model;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一工具异常输出。
 */
@Value
@Builder(toBuilder = true)
public class PlatformToolError {

    String code;

    String message;

    @Builder.Default
    boolean retriable = false;

    @Builder.Default
    Map<String, Object> details = new LinkedHashMap<>();
}
