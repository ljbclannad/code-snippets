package com.lx.governance.agentplatform.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.lx.governance.agentplatform.model.PromptRenderRequest;
import com.lx.governance.agentplatform.model.PromptRenderResult;
import com.lx.governance.agentplatform.model.PromptTemplateDescriptor;
import com.lx.governance.agentplatform.model.PromptTemplateType;

/**
 * 学习/本地验证用的内存版 Prompt 注册中心。
 */
public class InMemoryPromptRegistry implements PromptRegistry {

    private final Map<String, PromptTemplateRenderer> renderers = new LinkedHashMap<>();
    private final Map<String, String> activeVersions = new LinkedHashMap<>();

    public InMemoryPromptRegistry() {
    }

    public InMemoryPromptRegistry(List<PromptTemplateRenderer> initialRenderers) {
        if (initialRenderers != null) {
            initialRenderers.forEach(this::register);
        }
    }

    @Override
    public void register(PromptTemplateRenderer renderer) {
        Objects.requireNonNull(renderer, "renderer must not be null");
        PromptTemplateDescriptor descriptor = Objects.requireNonNull(renderer.descriptor(), "descriptor must not be null");
        String rendererKey = rendererKey(descriptor);
        renderers.put(rendererKey, renderer);
        activeVersions.putIfAbsent(activeKey(descriptor), descriptor.getVersion());
    }

    @Override
    public PromptRenderResult renderActive(
            String scene,
            String agentId,
            PromptTemplateType type,
            PromptRenderRequest request) {
        String version = findActiveVersion(scene, agentId, type)
                .orElseThrow(() -> new IllegalArgumentException("No active prompt version found"));
        return renderVersion(scene, agentId, type, version, request);
    }

    @Override
    public PromptRenderResult renderVersion(
            String scene,
            String agentId,
            PromptTemplateType type,
            String version,
            PromptRenderRequest request) {
        String key = rendererKey(scene, agentId, type, version);
        PromptTemplateRenderer renderer = renderers.get(key);
        if (renderer == null) {
            throw new IllegalArgumentException("Prompt template not found: " + key);
        }
        return PromptRenderResult.builder()
                .descriptor(renderer.descriptor())
                .content(renderer.render(request != null ? request : PromptRenderRequest.builder().build()))
                .build();
    }

    @Override
    public void setActiveVersion(String scene, String agentId, PromptTemplateType type, String version) {
        String key = rendererKey(scene, agentId, type, version);
        if (!renderers.containsKey(key)) {
            throw new IllegalArgumentException("Prompt template version not found: " + key);
        }
        activeVersions.put(activeKey(scene, agentId, type), version);
    }

    @Override
    public Optional<String> findActiveVersion(String scene, String agentId, PromptTemplateType type) {
        return Optional.ofNullable(activeVersions.get(activeKey(scene, agentId, type)));
    }

    @Override
    public List<PromptTemplateDescriptor> listTemplates(String scene, String agentId) {
        List<PromptTemplateDescriptor> descriptors = new ArrayList<>();
        for (PromptTemplateRenderer renderer : renderers.values()) {
            PromptTemplateDescriptor descriptor = renderer.descriptor();
            if (Objects.equals(scene, descriptor.getScene()) && Objects.equals(agentId, descriptor.getAgentId())) {
                descriptors.add(descriptor);
            }
        }
        return descriptors;
    }

    private String rendererKey(PromptTemplateDescriptor descriptor) {
        return rendererKey(
                descriptor.getScene(),
                descriptor.getAgentId(),
                descriptor.getType(),
                descriptor.getVersion());
    }

    private String rendererKey(String scene, String agentId, PromptTemplateType type, String version) {
        return safe(scene) + "|" + safe(agentId) + "|" + safe(type != null ? type.name() : null) + "|" + safe(version);
    }

    private String activeKey(PromptTemplateDescriptor descriptor) {
        return activeKey(descriptor.getScene(), descriptor.getAgentId(), descriptor.getType());
    }

    private String activeKey(String scene, String agentId, PromptTemplateType type) {
        return safe(scene) + "|" + safe(agentId) + "|" + safe(type != null ? type.name() : null);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
