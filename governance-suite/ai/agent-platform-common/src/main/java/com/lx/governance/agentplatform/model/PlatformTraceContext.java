package com.lx.governance.agentplatform.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台统一 trace 维度。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformTraceContext {

    private String traceId;

    private String conversationId;

    private String correlationId;

    private String runId;

    private String stepId;

    private String agentId;

    private String toolName;
}
