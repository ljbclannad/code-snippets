package com.example.chat;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/chat")
public class ChatBasicController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ChatBasicController(@Qualifier("DeepSeekChatClient") ChatClient chatClient, ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
    }

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

    record ActorFilms(String actor, List<String> movies) {
    }

    /**
     * 返回entity
     * 
     * @param userInput
     * @return
     */
    @GetMapping("/ai/record")
    List<ActorFilms> generationRecord(String userInput) {

        return this.chatClient.prompt()
                .user(userInput)
                .call()
                .entity(new ParameterizedTypeReference<List<ActorFilms>>() {

                });
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
    @GetMapping("/ai/prompt/template")
    ChatResponse promptTemplate() {
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
     * 对话记忆示例
     * 使用 {@link MessageChatMemoryAdvisor} 将历史消息自动追加到每次请求的上下文中，实现多轮对话。
     * - {@code conversationId} 用于隔离不同会话的历史记录，同一 id 的请求共享同一段记忆。
     * - {@link MessageChatMemoryAdvisor} 将记忆以 Message 列表的形式注入 prompt（区别于
     * {@code PromptChatMemoryAdvisor} 将记忆拼入 system text）。
     *
     * @param userInput      用户输入
     * @param conversationId 会话 ID，默认 "default"
     * @return 模型回复
     */
    @GetMapping("/ai/memory")
    String memory(@RequestParam String userInput,
            @RequestParam(defaultValue = "default") String conversationId) {
        return chatClient.prompt()
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory)
                        .conversationId(conversationId)
                        .build())
                .user(userInput)
                .call()
                .content();
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