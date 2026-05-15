package com.lx.governance.aichat.client;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring AI Prompt 构建测试
 * 验证迁移后的消息构建逻辑是否正确
 */
class SpringAiPromptConstructionTest {

    @Test
    void testPromptWithSystemAndUserMessages() {
        // 模拟 DeepSeekClient 中的 Prompt 构建逻辑
        String systemPrompt = "You are a helpful AI assistant.";
        String userMessage = "Hello, how are you?";

        Prompt prompt = new Prompt(
            List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userMessage)
            ),
            OpenAiChatOptions.builder()
                .withModel("deepseek-chat")
                .withTemperature(0.7)
                .withMaxTokens(2000)
                .build()
        );

        assertNotNull(prompt, "Prompt should not be null");
        assertEquals(2, prompt.getInstructions().size(), "Should have 2 messages");

        // 验证消息类型
        assertTrue(prompt.getInstructions().get(0) instanceof SystemMessage,
            "First message should be SystemMessage");
        assertTrue(prompt.getInstructions().get(1) instanceof UserMessage,
            "Second message should be UserMessage");

        // 验证消息内容
        assertEquals(systemPrompt, prompt.getInstructions().get(0).getContent(),
            "System message content should match");
        assertEquals(userMessage, prompt.getInstructions().get(1).getContent(),
            "User message content should match");
    }

    @Test
    void testOpenAiChatOptions() {
        // 验证 OpenAiChatOptions 配置
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .withModel("deepseek-chat")
            .withTemperature(0.7)
            .withMaxTokens(2000)
            .build();

        assertNotNull(options, "Options should not be null");
        assertEquals("deepseek-chat", options.getModel(), "Model should be deepseek-chat");
        assertEquals(0.7, options.getTemperature(), 0.001, "Temperature should be 0.7");
        assertEquals(2000, options.getMaxTokens(), "MaxTokens should be 2000");
    }

    @Test
    void testPromptWithEmptyMessages() {
        // 测试边界情况：空消息
        Prompt prompt = new Prompt(
            List.of(
                new SystemMessage(""),
                new UserMessage("")
            ),
            OpenAiChatOptions.builder()
                .withModel("deepseek-chat")
                .build()
        );

        assertNotNull(prompt);
        assertEquals(2, prompt.getInstructions().size());
    }

    @Test
    void testPromptWithLongMessages() {
        // 测试长消息
        String longSystemPrompt = "System: " + "A".repeat(1000);
        String longUserMessage = "User: " + "B".repeat(1000);

        Prompt prompt = new Prompt(
            List.of(
                new SystemMessage(longSystemPrompt),
                new UserMessage(longUserMessage)
            ),
            OpenAiChatOptions.builder()
                .withModel("deepseek-chat")
                .withTemperature(0.7)
                .withMaxTokens(2000)
                .build()
        );

        assertNotNull(prompt);
        assertEquals(longSystemPrompt, prompt.getInstructions().get(0).getContent());
        assertEquals(longUserMessage, prompt.getInstructions().get(1).getContent());
    }
}
