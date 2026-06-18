package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台上下文裁剪预算。
 */
@Value
@Builder(toBuilder = true)
public class PlatformContextBudget {

    Integer maxFields;

    Integer maxFieldChars;

    Integer maxTotalChars;

    Integer maxEvidences;

    Integer maxToolOutputs;
}
