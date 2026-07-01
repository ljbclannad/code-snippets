package com.health.agent.config;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.health.agent.engine.ReActPromptBuilder;
import com.health.agent.engine.subagent.AgentSubAgent;
import com.health.agent.scene.doctor.prompt.DoctorScenePromptTemplate;
import com.lx.governance.agentplatform.model.PromptRenderRequest;
import com.lx.governance.agentplatform.model.PromptTemplateDescriptor;
import com.lx.governance.agentplatform.model.PromptTemplateType;
import com.lx.governance.agentplatform.model.ToolDescriptor;
import com.lx.governance.agentplatform.runtime.InMemoryPromptRegistry;
import com.lx.governance.agentplatform.runtime.PromptRegistry;
import com.lx.governance.agentplatform.runtime.PromptTemplateRenderer;

/**
 * doctor 场景默认 Prompt 模板注册。
 */
@Configuration
public class DoctorPromptRegistryConfig {

    @Bean
    public PromptRegistry promptRegistry(List<PromptTemplateRenderer> renderers) {
        return new InMemoryPromptRegistry(renderers);
    }

    @Bean
    public PromptTemplateRenderer doctorSystemPromptTemplateRenderer(DoctorScenePromptTemplate template) {
        return new PromptTemplateRenderer() {
            @Override
            public PromptTemplateDescriptor descriptor() {
                return PromptTemplateDescriptor.builder()
                        .scene(ReActPromptBuilder.DOCTOR_SCENE)
                        .agentId(ReActPromptBuilder.DOCTOR_AGENT)
                        .version(ReActPromptBuilder.DEFAULT_PROMPT_VERSION)
                        .type(PromptTemplateType.SYSTEM)
                        .description("Doctor scene system prompt")
                        .build();
            }

            @Override
            public String render(PromptRenderRequest request) {
                List<ToolDescriptor> tools = toolsOf(request);
                return template.buildSystemPrompt(tools);
            }
        };
    }

    @Bean
    public PromptTemplateRenderer doctorSubAgentPromptTemplateRenderer(DoctorScenePromptTemplate template) {
        return new PromptTemplateRenderer() {
            @Override
            public PromptTemplateDescriptor descriptor() {
                return PromptTemplateDescriptor.builder()
                        .scene(ReActPromptBuilder.DOCTOR_SCENE)
                        .agentId(ReActPromptBuilder.DOCTOR_AGENT)
                        .version(ReActPromptBuilder.DEFAULT_PROMPT_VERSION)
                        .type(PromptTemplateType.SUB_AGENT_SYSTEM)
                        .description("Doctor scene sub-agent system prompt")
                        .build();
            }

            @Override
            public String render(PromptRenderRequest request) {
                Map<String, Object> variables = variablesOf(request);
                AgentSubAgent subAgent = (AgentSubAgent) variables.get("subAgent");
                return template.buildSubAgentSystemPrompt(subAgent, toolsOf(request));
            }
        };
    }

    @Bean
    public PromptTemplateRenderer doctorProtocolRepairPromptTemplateRenderer() {
        return new PromptTemplateRenderer() {
            @Override
            public PromptTemplateDescriptor descriptor() {
                return PromptTemplateDescriptor.builder()
                        .scene(ReActPromptBuilder.DOCTOR_SCENE)
                        .agentId(ReActPromptBuilder.DOCTOR_AGENT)
                        .version(ReActPromptBuilder.DEFAULT_PROMPT_VERSION)
                        .type(PromptTemplateType.PROTOCOL_REPAIR)
                        .description("Doctor scene protocol repair prompt")
                        .build();
            }

            @Override
            public String render(PromptRenderRequest request) {
                String errorMessage = String.valueOf(variablesOf(request).getOrDefault("errorMessage", ""));
                return """
                    你刚才的响应不符合协议，错误是：%s

                    请立即重新输出，并且：
                    1. 只输出一个合法 JSON 对象
                    2. protocolVersion 必须为 "v1"
                    3. 不要输出 Markdown 代码块
                    4. 不要解释错误，不要附加多余文本
                    """.formatted(errorMessage);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<ToolDescriptor> toolsOf(PromptRenderRequest request) {
        Object tools = variablesOf(request).get("availableTools");
        return tools instanceof List<?> list ? (List<ToolDescriptor>) list : List.of();
    }

    private static Map<String, Object> variablesOf(PromptRenderRequest request) {
        return request != null && request.getVariables() != null ? request.getVariables() : Map.of();
    }
}
