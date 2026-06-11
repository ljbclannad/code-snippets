package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式响应事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamEvent {

    /**
     * 事件类型
     */
    private EventType type;

    /**
     * 内容片段（当 type=CONTENT 时）
     */
    private String content;

    /**
     * 会话ID（当 type=START 或 DONE 时）
     */
    private String conversationId;

    /**
     * 错误信息（当 type=ERROR 时）
     */
    private String error;

    /**
     * 错误码（当 type=ERROR 时）
     */
    private Integer errorCode;

    /**
     * Token 使用情况（当 type=DONE 时）
     */
    private TokenUsage tokenUsage;

    /**
     * 时间戳
     */
    private Long timestamp;

    public enum EventType {
        START,      // 开始
        CONTENT,    // 内容片段
        DONE,       // 完成
        ERROR       // 错误
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenUsage {
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }

    public static StreamEvent start(String conversationId) {
        return StreamEvent.builder()
                .type(EventType.START)
                .conversationId(conversationId)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static StreamEvent content(String content) {
        return StreamEvent.builder()
                .type(EventType.CONTENT)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static StreamEvent done(String conversationId, TokenUsage tokenUsage) {
        return StreamEvent.builder()
                .type(EventType.DONE)
                .conversationId(conversationId)
                .tokenUsage(tokenUsage)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static StreamEvent error(String error, Integer errorCode) {
        return StreamEvent.builder()
                .type(EventType.ERROR)
                .error(error)
                .errorCode(errorCode)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
