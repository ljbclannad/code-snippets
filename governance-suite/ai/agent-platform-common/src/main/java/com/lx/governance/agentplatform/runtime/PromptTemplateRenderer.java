package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.PromptRenderRequest;
import com.lx.governance.agentplatform.model.PromptTemplateDescriptor;

/**
 * Prompt 模板渲染器。
 * <p>
 * 一个渲染器通常对应一个场景、agent 和模板类型组合。
 */
public interface PromptTemplateRenderer {

    PromptTemplateDescriptor descriptor();

    String render(PromptRenderRequest request);
}
