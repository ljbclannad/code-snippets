package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * Prompt 渲染结果。
 */
@Value
@Builder(toBuilder = true)
public class PromptRenderResult {

    PromptTemplateDescriptor descriptor;

    String content;
}
