package com.lx.governance.aichat.exception;

/**
 * AI 服务相关异常
 */
public class AiServiceException extends AiChatException {

    public AiServiceException(ErrorCode errorCode) {
        super(errorCode);
    }

    public AiServiceException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public AiServiceException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public AiServiceException(ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }

    public static AiServiceException apiError(Throwable cause) {
        return new AiServiceException(ErrorCode.AI_API_ERROR, cause);
    }

    public static AiServiceException apiError(String detail, Throwable cause) {
        return new AiServiceException(ErrorCode.AI_API_ERROR, detail, cause);
    }

    public static AiServiceException timeout(String detail) {
        return new AiServiceException(ErrorCode.AI_API_TIMEOUT, detail);
    }

    public static AiServiceException rateLimit() {
        return new AiServiceException(ErrorCode.AI_API_RATE_LIMIT);
    }

    public static AiServiceException tokenLimitExceeded(int used, int limit) {
        return new AiServiceException(
            ErrorCode.TOKEN_LIMIT_EXCEEDED,
            String.format("Token 使用量 %d 超过限制 %d", used, limit)
        );
    }
}
