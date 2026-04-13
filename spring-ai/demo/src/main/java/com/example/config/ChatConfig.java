package com.example.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {

    /**
     * 默认系统文本配置和默认顾问
     * 
     * @param chatClientBuilder
     * @return
     */
    /**
     * 对话记忆保留最近 10 条消息的滑动窗口。
     * ChatMemoryRepository 由 Spring AI 自动配置；引入 JDBC starter 后使用
     * JdbcChatMemoryRepository。
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();
    }

    @Bean
    public ChatClient DeepSeekChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
                .defaultSystem(
                        s -> s.text("You are a helpful assistant. The language is {language}").param("language",
                                "Chinese"))
                .defaultAdvisors(new SimpleLoggerAdvisor(
                        request -> "Custom request: " + request.prompt().getUserMessage(),
                        response -> "Custom response: " + response.getResult(),
                        0))
                .build();
    }

}
