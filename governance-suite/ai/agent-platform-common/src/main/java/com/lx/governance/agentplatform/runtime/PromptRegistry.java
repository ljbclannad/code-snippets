package com.lx.governance.agentplatform.runtime;

import java.util.List;
import java.util.Optional;

import com.lx.governance.agentplatform.model.PromptRenderRequest;
import com.lx.governance.agentplatform.model.PromptRenderResult;
import com.lx.governance.agentplatform.model.PromptTemplateDescriptor;
import com.lx.governance.agentplatform.model.PromptTemplateType;

/**
 * 平台 Prompt 模板注册中心。
 */
public interface PromptRegistry {

    void register(PromptTemplateRenderer renderer);

    PromptRenderResult renderActive(
            String scene,
            String agentId,
            PromptTemplateType type,
            PromptRenderRequest request);

    PromptRenderResult renderVersion(
            String scene,
            String agentId,
            PromptTemplateType type,
            String version,
            PromptRenderRequest request);

    void setActiveVersion(String scene, String agentId, PromptTemplateType type, String version);

    Optional<String> findActiveVersion(String scene, String agentId, PromptTemplateType type);

    List<PromptTemplateDescriptor> listTemplates(String scene, String agentId);
}
