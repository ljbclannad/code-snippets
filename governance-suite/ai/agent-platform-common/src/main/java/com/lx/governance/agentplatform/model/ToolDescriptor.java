package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一工具描述。
 */
@Value
@Builder(toBuilder = true)
public class ToolDescriptor {

    String toolName;

    String description;

    String riskLevel;

    Map<String, Object> inputSchema;
}
