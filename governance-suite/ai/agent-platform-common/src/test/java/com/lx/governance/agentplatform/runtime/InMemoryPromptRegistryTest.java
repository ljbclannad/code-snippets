package com.lx.governance.agentplatform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lx.governance.agentplatform.model.PromptRenderRequest;
import com.lx.governance.agentplatform.model.PromptTemplateDescriptor;
import com.lx.governance.agentplatform.model.PromptTemplateType;

class InMemoryPromptRegistryTest {

    @Test
    void shouldRenderActivePromptAndSwitchVersion() {
        InMemoryPromptRegistry registry = new InMemoryPromptRegistry(List.of(
                renderer("doctor-scene", "doctor-agent", "v1", "system-v1"),
                renderer("doctor-scene", "doctor-agent", "v2", "system-v2")));

        assertEquals("v1", registry.findActiveVersion("doctor-scene", "doctor-agent", PromptTemplateType.SYSTEM)
                .orElseThrow());
        assertEquals("system-v1", registry.renderActive(
                "doctor-scene",
                "doctor-agent",
                PromptTemplateType.SYSTEM,
                PromptRenderRequest.builder().build()).getContent());

        registry.setActiveVersion("doctor-scene", "doctor-agent", PromptTemplateType.SYSTEM, "v2");

        assertEquals("v2", registry.findActiveVersion("doctor-scene", "doctor-agent", PromptTemplateType.SYSTEM)
                .orElseThrow());
        assertEquals("system-v2", registry.renderActive(
                "doctor-scene",
                "doctor-agent",
                PromptTemplateType.SYSTEM,
                PromptRenderRequest.builder().build()).getContent());
    }

    @Test
    void shouldListTemplatesBySceneAndAgent() {
        InMemoryPromptRegistry registry = new InMemoryPromptRegistry(List.of(
                renderer("doctor-scene", "doctor-agent", "v1", "system-v1"),
                renderer("doctor-scene", "doctor-agent", "v2", "system-v2"),
                renderer("other-scene", "doctor-agent", "v1", "other")));

        assertEquals(2, registry.listTemplates("doctor-scene", "doctor-agent").size());
    }

    @Test
    void shouldRejectUnknownActiveVersion() {
        InMemoryPromptRegistry registry = new InMemoryPromptRegistry(List.of(
                renderer("doctor-scene", "doctor-agent", "v1", "system-v1")));

        assertThrows(IllegalArgumentException.class,
                () -> registry.setActiveVersion("doctor-scene", "doctor-agent", PromptTemplateType.SYSTEM, "v9"));
    }

    private PromptTemplateRenderer renderer(
            String scene,
            String agentId,
            String version,
            String content) {
        return new PromptTemplateRenderer() {
            @Override
            public PromptTemplateDescriptor descriptor() {
                return PromptTemplateDescriptor.builder()
                        .scene(scene)
                        .agentId(agentId)
                        .version(version)
                        .type(PromptTemplateType.SYSTEM)
                        .description(content)
                        .build();
            }

            @Override
            public String render(PromptRenderRequest request) {
                return content;
            }
        };
    }
}
