package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台上下文裁剪预算。
 * <p>
 * 用来限制投影后的字段、证据和工具输出规模。
 */
@Value
@Builder(toBuilder = true)
public class PlatformContextBudget {

    /** 最大字段数。 */
    Integer maxFields;

    /** 单字段最大字符数。 */
    Integer maxFieldChars;

    /** 全部字段总字符上限。 */
    Integer maxTotalChars;

    /** 最大证据数。 */
    Integer maxEvidences;

    /** 最大工具输出数。 */
    Integer maxToolOutputs;
}
