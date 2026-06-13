package com.health.agent.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 消息
 */
@Data
@AllArgsConstructor
public class Message {

    /**
     * 角色：user 或 assistant
     */
    private String role;

    /**
     * 内容
     */
    private String content;

    /**
     * 创建用户消息
     */
    public static Message user(String content) {
        return new Message("user", content);
    }

    /**
     * 创建助手消息
     */
    public static Message assistant(String content) {
        return new Message("assistant", content);
    }
}
