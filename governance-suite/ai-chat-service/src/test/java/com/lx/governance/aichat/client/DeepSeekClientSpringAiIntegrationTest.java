package com.lx.governance.aichat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Spring AI 集成测试
 * 验证 ChatModel 是否正确配置和工作
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.ai.openai.base-url=https://api.deepseek.com",
        "spring.ai.openai.chat.options.model=deepseek-chat"
})
class DeepSeekClientSpringAiIntegrationTest {

    @Autowired(required = false)
    private ChatModel chatModel;

    @Test
    void testChatModelBeanExists() {
        assertNotNull(chatModel, "ChatModel bean should be auto-configured by Spring AI");
    }

    @Test
    void testPromptConstruction() {
        // 验证 Prompt 构建逻辑
        String systemPrompt = "You are a helpful assistant.";
        String userMessage = "Hello!";

        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userMessage)),
                OpenAiChatOptions.builder()
                        .withModel("deepseek-chat")
                        .withTemperature(0.7)
                        .withMaxTokens(2000)
                        .build());

        assertNotNull(prompt);
        assertEquals(2, prompt.getInstructions().size());
        assertEquals("deepseek-chat", prompt.getOptions().getModel());
    }

    @Test
    void testChatModelConfiguration() {
        // 验证 ChatModel 配置
        assertNotNull(chatModel, "ChatModel should be configured");

        // 注意：实际调用需要有效的 API key，这里只验证配置
        // 如果需要测试实际调用，需要使用 @Disabled 或 mock
    }
}
