package com.example.prompts;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词构建
 * 
 * @see https://docs.spring.io/spring-ai/reference/_images/spring-ai-message-api.jpg
 */
@RestController
@RequestMapping("/prompts")
public class PromptBasicController {

    @Autowired(required = true)
    private ChatClient chatClient;

}
