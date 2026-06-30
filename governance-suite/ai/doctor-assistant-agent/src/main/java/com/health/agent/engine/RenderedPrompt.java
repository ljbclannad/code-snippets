package com.health.agent.engine;

import com.lx.governance.agentplatform.model.PromptTemplateDescriptor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prompt 渲染结果，包含内容和模板描述信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenderedPrompt {

    private PromptTemplateDescriptor descriptor;

    private String content;
}
