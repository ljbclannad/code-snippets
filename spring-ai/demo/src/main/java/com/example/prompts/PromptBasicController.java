package com.example.prompts;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatPromptTemplate;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词构建
 */
@RestController
@RequestMapping("/prompts")
public class PromptBasicController {

    private final ChatClient chatClient;

    public PromptBasicController(@Qualifier("DeepSeekChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    record PromptMessageView(String role, String text) {
    }

    record RenderTemplateUsage(String template, Map<String, Object> params, String rendered) {
    }

    /**
     * 只渲染提示词模板，不调用模型。
     */
    @GetMapping("/template/render")
    String renderTemplate(@RequestParam(defaultValue = "Spring AI") String topic,
            @RequestParam(defaultValue = "3") int count) {
        PromptTemplate promptTemplate = new PromptTemplate("请用 {count} 条要点介绍 {topic} 的核心概念。");

        return promptTemplate.render(Map.of("topic", topic, "count", count));
    }

    /**
     * renderTemplate 的完整使用方法：
     * 1. 在模板中使用 {变量名} 定义占位符。
     * 2. 用 Map 传入同名参数。
     * 3. 调用 render(params) 得到最终提示词文本。
     */
    @GetMapping("/template/render/usage")
    RenderTemplateUsage renderTemplateUsage(@RequestParam(defaultValue = "Spring AI") String topic,
            @RequestParam(defaultValue = "入门教程") String scene,
            @RequestParam(defaultValue = "5") int count) {
        String template = """
                请为 {scene} 场景生成一段关于 {topic} 的提示词。
                要求：
                1. 输出 {count} 个要点。
                2. 每个要点不超过 30 字。
                3. 使用中文回答。
                """;
        Map<String, Object> params = Map.of(
                "topic", topic,
                "scene", scene,
                "count", count);
        PromptTemplate promptTemplate = new PromptTemplate(template);

        return new RenderTemplateUsage(template, params, promptTemplate.render(params));
    }

    /**
     * 使用 PromptTemplate 创建 UserMessage。
     */
    @GetMapping("/template/message")
    PromptMessageView createMessage(@RequestParam(defaultValue = "RAG") String topic) {
        Message message = new PromptTemplate("请给初学者解释 {topic}，控制在 100 字以内。")
                .createMessage(Map.of("topic", topic));

        return new PromptMessageView(message.getMessageType().getValue(), message.getText());
    }

    /**
     * 使用 PromptTemplate 创建 Prompt，再交给 ChatClient 调用。
     */
    @GetMapping("/template/call")
    String callWithPromptTemplate(@RequestParam(defaultValue = "Spring AI PromptTemplate") String topic) {
        Prompt prompt = new PromptTemplate("请给出 {topic} 的一个 Java 使用示例，并说明适用场景。")
                .create(Map.of("topic", topic));

        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    /**
     * 分别构建 system 和 user 消息，适合明确约束模型角色和输出格式。
     */
    @GetMapping("/system-user")
    ChatResponse systemAndUserPrompt(@RequestParam(defaultValue = "Spring AI 的 ChatClient") String topic,
            @RequestParam(defaultValue = "中文") String language) {
        Message systemMessage = new SystemPromptTemplate(
                "你是一个资深 Java 讲师。请使用 {language} 回答，并给出结构化小标题。")
                .createMessage(Map.of("language", language));

        Message userMessage = new PromptTemplate("讲解 {topic} 的常用写法，并给出一个简短示例。")
                .createMessage(Map.of("topic", topic));

        return chatClient.prompt(new Prompt(List.of(systemMessage, userMessage)))
                .call()
                .chatResponse();
    }

    /**
     * 使用 ChatPromptTemplate 一次性组织多条消息模板。
     */
    @GetMapping("/chat-template")
    String chatPromptTemplate(@RequestParam(defaultValue = "PromptTemplate") String concept,
            @RequestParam(defaultValue = "面试准备") String scene) {
        ChatPromptTemplate chatPromptTemplate = new ChatPromptTemplate(List.of(
                new SystemPromptTemplate("你是一个擅长把技术概念讲清楚的助手，回答要贴合 {scene} 场景。"),
                new PromptTemplate("请解释 {concept}，包括用途、关键 API 和一个常见坑。")));

        Prompt prompt = chatPromptTemplate.create(Map.of("concept", concept, "scene", scene));

        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    /**
     * 直接手工构造 Message 列表，适合已有消息历史或需要完全控制角色顺序的场景。
     */
    @GetMapping("/messages")
    String messages(@RequestParam(defaultValue = "Spring AI 中 Prompt 和 Message 的区别") String question) {
        List<Message> messages = List.of(
                new SystemMessage("你是一个回答精炼的 Spring AI 助手。"),
                new UserMessage(question));

        return chatClient.prompt()
                .messages(messages)
                .call()
                .content();
    }

}
