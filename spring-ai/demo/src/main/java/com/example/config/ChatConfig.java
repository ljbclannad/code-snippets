package com.example.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
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
