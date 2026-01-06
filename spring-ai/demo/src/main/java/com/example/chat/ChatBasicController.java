package com.example.chat;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/chat")
public class ChatBasicController {

    @Autowired(required = true)
    private ChatClient chatClient;

    /**
     * 普通输出
     * 
     * @param userInput
     * @return
     */
    @GetMapping("/ai")
    String generation(String userInput) {
        return this.chatClient.prompt()
                .user(userInput)
                .call()
                .content();
    }

    /**
     * 流式输出
     * 
     * @param userInput
     * @return
     */
    @GetMapping("/ai/stream")
    Flux<String> stream(String userInput) {
        Flux<String> output = chatClient.prompt()
                .user("Tell me a joke")
                .stream()
                .content();
        return output;
    }

    /**
     * 提示词构建
     * 使用 {@link ChatClient#prompt()} 方法构建提示词，并使用 {@link ChatClient.PromptUserSpec}
     * 和 {@link ChatClient.PromptSystemSpec} 方法构建提示词。
     * 
     * @return
     */
    @GetMapping("/ai/prompt")
    ChatResponse prompt() {
        ChatResponse answer = chatClient.prompt()
                .advisors(new SimpleLoggerAdvisor(
                        request -> "Custom request: " + request.prompt().getUserMessage(),
                        response -> "Custom response: " + response.getResult(),
                        0))
                .user(u -> u
                        .text("Tell me the names of 5 movies whose soundtrack was composed by {composer}, and the year of the movie between {year1} and {year2}")
                        .params(Map.of("composer", "John Williams", "year1", "1990", "year2", "2025")))
                .call()
                .chatResponse();
        return answer;
    }

    /**
     * 使用 {@link ChatClient#advisors()} 方法添加多个顾问
     * 
     * @see https://docs.spring.io/spring-ai/reference/_images/advisors-flow.jpg
     * @return
     */
    @GetMapping("/ai/advisor")
    ChatResponse advisor() {
        return chatClient.prompt()
                .advisors(new SimpleLoggerAdvisor(
                        request -> "Custom request: " + request.prompt().getUserMessage(),
                        response -> "Custom response: " + response.getResult(),
                        0))
                .user(u -> u.text(
                        "Tell me the names of 5 movies whose soundtrack was composed by {composer}, and the year of the movie between {year1} and {year2}")
                        .params(Map.of("composer", "John Williams", "year1", "1990", "year2", "2025")))
                .call()
                .chatResponse();
    }

}