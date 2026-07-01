package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一 Prompt 模板描述。
 */
@Value
@Builder(toBuilder = true)
public class PromptTemplateDescriptor {

    String scene;

    String agentId;

    String version;

    PromptTemplateType type;

    String description;
}
