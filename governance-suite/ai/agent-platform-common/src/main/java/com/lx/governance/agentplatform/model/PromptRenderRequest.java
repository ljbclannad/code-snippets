package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * Prompt 渲染请求。
 */
@Value
@Builder(toBuilder = true)
public class PromptRenderRequest {

    @Singular("variable")
    Map<String, Object> variables;
}
