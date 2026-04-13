package com.example.memory;

import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JdbcChatMemoryRepository 测试接口。
 */
@RestController
@RequestMapping("/memory/jdbc")
public class JdbcChatMemoryTestController {

    private final JdbcChatMemoryRepository jdbcChatMemoryRepository;
    private final ChatMemory chatMemory;

    public JdbcChatMemoryTestController(JdbcChatMemoryRepository jdbcChatMemoryRepository, ChatMemory chatMemory) {
        this.jdbcChatMemoryRepository = jdbcChatMemoryRepository;
        this.chatMemory = chatMemory;
    }

    record MessageView(String role, String text) {
    }

    record ConversationView(String conversationId, List<MessageView> messages) {
    }

    /**
     * 直接使用 JdbcChatMemoryRepository 保存一段测试会话。
     * saveAll 会替换同 conversationId 下的所有已有消息。
     */
    @PostMapping("/save")
    ConversationView save(@RequestParam(defaultValue = "jdbc-test") String conversationId,
            @RequestParam(defaultValue = "Spring AI 的 JDBC memory 怎么用？") String userText,
            @RequestParam(defaultValue = "引入 JDBC repository starter 后，通过 ChatMemoryRepository 持久化消息。") String assistantText) {
        List<Message> messages = List.of(
                new UserMessage(userText),
                new AssistantMessage(assistantText));

        jdbcChatMemoryRepository.saveAll(conversationId, messages);

        return find(conversationId);
    }

    /**
     * 直接从 JdbcChatMemoryRepository 查询某个会话的消息。
     */
    @GetMapping("/find")
    ConversationView find(@RequestParam(defaultValue = "jdbc-test") String conversationId) {
        List<MessageView> messages = jdbcChatMemoryRepository.findByConversationId(conversationId)
                .stream()
                .map(this::toView)
                .toList();

        return new ConversationView(conversationId, messages);
    }

    /**
     * 查看当前 JDBC memory 里已有的 conversationId。
     */
    @GetMapping("/ids")
    List<String> ids() {
        return jdbcChatMemoryRepository.findConversationIds();
    }

    /**
     * 通过 MessageWindowChatMemory 写入消息。
     * 项目里的 ChatMemory Bean 底层同样使用 JdbcChatMemoryRepository。
     */
    @PostMapping("/window/add")
    ConversationView addWithChatMemory(@RequestParam(defaultValue = "jdbc-window-test") String conversationId,
            @RequestParam(defaultValue = "帮我记住：Spring AI memory 只保存模型上下文。") String text) {
        chatMemory.add(conversationId, List.of(new UserMessage(text)));

        return find(conversationId);
    }

    /**
     * 清空指定会话。
     */
    @DeleteMapping("/clear")
    List<String> clear(@RequestParam(defaultValue = "jdbc-test") String conversationId) {
        jdbcChatMemoryRepository.deleteByConversationId(conversationId);

        return jdbcChatMemoryRepository.findConversationIds();
    }

    private MessageView toView(Message message) {
        return new MessageView(message.getMessageType().getValue(), message.getText());
    }

}
