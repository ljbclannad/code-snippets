package com.lx.governance.aichat.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConversationMessage 测试类
 */
class ConversationMessageTest {

    @Test
    void testBuilder() {
        LocalDateTime now = LocalDateTime.now();
        ConversationMessage message = ConversationMessage.builder()
                .role(ConversationMessage.Role.USER)
                .content("Hello")
                .timestamp(now)
                .tokenCount(10)
                .build();

        assertEquals(ConversationMessage.Role.USER, message.getRole());
        assertEquals("Hello", message.getContent());
        assertEquals(now, message.getTimestamp());
        assertEquals(10, message.getTokenCount());
    }

    @Test
    void testRoleEnum() {
        assertEquals("user", ConversationMessage.Role.USER.getValue());
        assertEquals("assistant", ConversationMessage.Role.ASSISTANT.getValue());
    }

    @Test
    void testSettersAndGetters() {
        ConversationMessage message = new ConversationMessage();
        LocalDateTime now = LocalDateTime.now();

        message.setRole(ConversationMessage.Role.ASSISTANT);
        message.setContent("Response");
        message.setTimestamp(now);
        message.setTokenCount(20);

        assertEquals(ConversationMessage.Role.ASSISTANT, message.getRole());
        assertEquals("Response", message.getContent());
        assertEquals(now, message.getTimestamp());
        assertEquals(20, message.getTokenCount());
    }
}
