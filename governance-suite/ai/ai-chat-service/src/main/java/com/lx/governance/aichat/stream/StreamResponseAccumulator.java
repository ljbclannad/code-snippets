package com.lx.governance.aichat.stream;

import com.lx.governance.aichat.dto.StreamEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式响应累积器
 * 用于在流式传输过程中累积完整的响应内容
 */
@Slf4j
public class StreamResponseAccumulator {

    private final StringBuilder contentBuilder = new StringBuilder();
    private final AtomicReference<String> conversationId = new AtomicReference<>();
    private final AtomicReference<StreamEvent.TokenUsage> tokenUsage = new AtomicReference<>();
    private volatile boolean completed = false;
    private volatile boolean hasError = false;
    private volatile String errorMessage;

    /**
     * 累积流事件
     */
    public void accumulate(StreamEvent event) {
        switch (event.getType()) {
            case START:
                conversationId.set(event.getConversationId());
                log.debug("流式响应开始 - conversationId: {}", event.getConversationId());
                break;

            case CONTENT:
                if (event.getContent() != null) {
                    contentBuilder.append(event.getContent());
                }
                break;

            case DONE:
                completed = true;
                if (event.getTokenUsage() != null) {
                    tokenUsage.set(event.getTokenUsage());
                }
                log.debug("流式响应完成 - conversationId: {}, totalTokens: {}",
                        event.getConversationId(),
                        event.getTokenUsage() != null ? event.getTokenUsage().getTotalTokens() : "N/A");
                break;

            case ERROR:
                hasError = true;
                errorMessage = event.getError();
                log.error("流式响应错误: {}", event.getError());
                break;

            case STATUS:
                break;
        }
    }

    /**
     * 获取累积的完整内容
     */
    public String getFullContent() {
        return contentBuilder.toString();
    }

    /**
     * 获取会话ID
     */
    public String getConversationId() {
        return conversationId.get();
    }

    /**
     * 获取 Token 使用情况
     */
    public StreamEvent.TokenUsage getTokenUsage() {
        return tokenUsage.get();
    }

    /**
     * 是否完成
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * 是否有错误
     */
    public boolean hasError() {
        return hasError;
    }

    /**
     * 获取错误消息
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 重置累积器
     */
    public void reset() {
        contentBuilder.setLength(0);
        conversationId.set(null);
        tokenUsage.set(null);
        completed = false;
        hasError = false;
        errorMessage = null;
    }
}
