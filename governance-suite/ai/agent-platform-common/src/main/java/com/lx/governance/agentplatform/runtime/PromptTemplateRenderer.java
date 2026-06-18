package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.PromptRenderRequest;
import com.lx.governance.agentplatform.model.PromptTemplateDescriptor;

/**
 * Prompt 模板渲染器。
 */
public interface PromptTemplateRenderer {

    PromptTemplateDescriptor descriptor();

    String render(PromptRenderRequest request);
}
