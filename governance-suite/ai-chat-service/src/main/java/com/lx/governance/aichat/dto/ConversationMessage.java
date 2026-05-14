package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话消息数据模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {

    /**
     * 消息角色
     */
    private Role role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 时间戳
     */
    private LocalDateTime timestamp;

    /**
     * Token 数量（估算值）
     */
    private Integer tokenCount;

    /**
     * 消息角色枚举
     */
    public enum Role {
        USER("user"),
        ASSISTANT("assistant");

        private final String value;

        Role(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
