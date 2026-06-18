package com.lx.governance.agentplatform.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台统一工具输出载荷。
 * <p>
 * 既承载工具结果，也承载对上下文的增量更新和证据。
 */
@Value
@Builder(toBuilder = true)
public class PlatformToolOutput {

    /** 工具名。 */
    String toolName;

    /** 人类可读摘要。 */
    String summary;

    /** 对外可见性。 */
    PlatformContextVisibility visibility;

    /** 原始数据。 */
    Object data;

    /** 需要合并进上下文的状态变更。 */
    @Builder.Default
    Map<String, Object> stateUpdates = new LinkedHashMap<>();

    /** 证据列表。 */
    @Singular("evidence")
    List<PlatformContextEvidence> evidences;
}
