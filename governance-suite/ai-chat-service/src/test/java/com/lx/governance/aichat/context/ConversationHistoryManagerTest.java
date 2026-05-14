package com.lx.governance.aichat.context;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.ConversationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConversationHistoryManager 测试类
 */
class ConversationHistoryManagerTest {

    private ConversationHistoryManager manager;
    private ContextBudgetProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ContextBudgetProperties();
        ContextBudgetProperties.MemoryConfig memoryConfig = new ContextBudgetProperties.MemoryConfig();
        memoryConfig.setShortTermDays(3);
        memoryConfig.setMidTermDays(30);
        properties.setMemory(memoryConfig);

        manager = new ConversationHistoryManager(properties);
    }

    @Test
    void testFormatMessages() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.builder()
                .role(ConversationMessage.Role.USER)
                .content("Hello")
                .timestamp(LocalDateTime.now())
                .build());
        messages.add(ConversationMessage.builder()
                .role(ConversationMessage.Role.ASSISTANT)
                .content("Hi there")
                .timestamp(LocalDateTime.now())
                .build());

        String formatted = manager.formatMessages(messages);

        assertTrue(formatted.contains("user: Hello"));
        assertTrue(formatted.contains("assistant: Hi there"));
    }

    @Test
    void testFormatMessages_emptyList() {
        String formatted = manager.formatMessages(new ArrayList<>());
        assertEquals("", formatted);
    }

    @Test
    void testSelectConversationHistory_withinBudget() {
        List<ConversationMessage> messages = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // 添加短期消息
        messages.add(ConversationMessage.builder()
                .role(ConversationMessage.Role.USER)
                .content("短期消息1")
                .timestamp(now.minusDays(1))
                .build());
        messages.add(ConversationMessage.builder()
                .role(ConversationMessage.Role.ASSISTANT)
                .content("短期回复1")
                .timestamp(now.minusDays(1))
                .build());

        String result = manager.selectConversationHistory(messages, 1000);

        assertNotNull(result);
        assertTrue(result.contains("短期消息1"));
        assertTrue(result.contains("短期回复1"));
    }

    @Test
    void testSelectConversationHistory_exceedsBudget() {
        List<ConversationMessage> messages = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // 添加多条短期消息
        for (int i = 0; i < 10; i++) {
            messages.add(ConversationMessage.builder()
                    .role(ConversationMessage.Role.USER)
                    .content("这是一条很长的消息内容，用于测试预算限制功能" + i)
                    .timestamp(now.minusDays(1))
                    .build());
        }

        // 预算很小，应该只包含部分消息
        String result = manager.selectConversationHistory(messages, 50);

        assertNotNull(result);
        // 结果应该比所有消息的总和短
        String allMessages = manager.formatMessages(messages);
        assertTrue(result.length() < allMessages.length());
    }

    @Test
    void testSelectConversationHistory_withMidTermMessages() {
        List<ConversationMessage> messages = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // 添加短期消息
        messages.add(ConversationMessage.builder()
                .role(ConversationMessage.Role.USER)
                .content("短期消息")
                .timestamp(now.minusDays(1))
                .build());

        // 添加中期消息
        messages.add(ConversationMessage.builder()
                .role(ConversationMessage.Role.USER)
                .content("中期消息")
                .timestamp(now.minusDays(10))
                .build());

        String result = manager.selectConversationHistory(messages, 1000);

        assertNotNull(result);
        // 短期消息应该被包含
        assertTrue(result.contains("短期消息"));
    }

    @Test
    void testSelectConversationHistory_withLongTermMessages() {
        List<ConversationMessage> messages = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // 添加长期消息
        for (int i = 0; i < 5; i++) {
            messages.add(ConversationMessage.builder()
                    .role(ConversationMessage.Role.USER)
                    .content("长期消息" + i)
                    .timestamp(now.minusDays(60))
                    .build());
        }

        String result = manager.selectConversationHistory(messages, 1000);

        assertNotNull(result);
        // 应该包含摘要
        assertTrue(result.contains("历史摘要") || result.contains("共"));
    }

    @Test
    void testSelectConversationHistory_emptyMessages() {
        String result = manager.selectConversationHistory(new ArrayList<>(), 1000);
        assertEquals("", result);
    }

    @Test
    void testSelectConversationHistory_nullMessages() {
        String result = manager.selectConversationHistory(null, 1000);
        assertEquals("", result);
    }
}
