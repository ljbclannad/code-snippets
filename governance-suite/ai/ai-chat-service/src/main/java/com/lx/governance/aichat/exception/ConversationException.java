package com.lx.governance.aichat.exception;

/**
 * 对话相关异常
 */
public class ConversationException extends AiChatException {

    public ConversationException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ConversationException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public ConversationException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public static ConversationException busy(String conversationId) {
        return new ConversationException(
            ErrorCode.CONVERSATION_BUSY,
            "会话 " + conversationId + " 正在处理中"
        );
    }

    public static ConversationException notFound(String conversationId) {
        return new ConversationException(
            ErrorCode.CONVERSATION_NOT_FOUND,
            "会话 " + conversationId + " 不存在"
        );
    }

    public static ConversationException messageTooLong(int length, int maxLength) {
        return new ConversationException(
            ErrorCode.MESSAGE_TOO_LONG,
            String.format("消息长度 %d 超过最大限制 %d", length, maxLength)
        );
    }

    public static ConversationException messageEmpty() {
        return new ConversationException(ErrorCode.MESSAGE_EMPTY);
    }
}
