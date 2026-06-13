package com.lx.governance.aichat.exception;

import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
public enum ErrorCode {

    // 通用错误 (1000-1999)
    SUCCESS(0, "成功"),
    UNKNOWN_ERROR(1000, "未知错误"),
    INVALID_PARAMETER(1001, "参数错误"),
    SERVICE_UNAVAILABLE(1002, "服务不可用"),
    REQUEST_TIMEOUT(1003, "请求超时"),

    // 对话相关错误 (2000-2999)
    CONVERSATION_BUSY(2001, "对话正在处理中，请稍后重试"),
    CONVERSATION_NOT_FOUND(2002, "对话不存在"),
    MESSAGE_TOO_LONG(2003, "消息内容过长"),
    MESSAGE_EMPTY(2004, "消息内容为空"),
    CONVERSATION_CONTEXT_ERROR(2005, "对话上下文错误"),

    // AI 服务错误 (3000-3999)
    AI_API_ERROR(3001, "AI 服务调用失败"),
    AI_API_TIMEOUT(3002, "AI 服务响应超时"),
    AI_API_RATE_LIMIT(3003, "AI 服务请求过于频繁"),
    AI_RESPONSE_INVALID(3004, "AI 响应格式无效"),
    TOKEN_LIMIT_EXCEEDED(3005, "Token 使用量超出限制"),

    // 用户画像错误 (4000-4999)
    USER_PROFILE_NOT_FOUND(4001, "用户画像不存在"),
    USER_PROFILE_FETCH_ERROR(4002, "获取用户画像失败"),
    PROFILE_COMPLETION_ERROR(4003, "画像补全失败"),

    // 记忆服务错误 (5000-5999)
    MEMORY_FETCH_ERROR(5001, "获取记忆失败"),
    MEMORY_SAVE_ERROR(5002, "保存记忆失败"),
    VECTOR_SEARCH_ERROR(5003, "向量搜索失败"),

    // 历史记录错误 (6000-6999)
    CHAT_HISTORY_SAVE_ERROR(6001, "保存聊天历史失败"),
    CHAT_HISTORY_SEARCH_ERROR(6002, "搜索聊天历史失败"),

    // 限流和配额错误 (7000-7999)
    RATE_LIMIT_EXCEEDED(7001, "请求频率超出限制"),
    QUOTA_EXCEEDED(7002, "配额已用尽"),
    CONCURRENT_LIMIT_EXCEEDED(7003, "并发请求数超出限制");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
