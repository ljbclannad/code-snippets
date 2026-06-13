package com.lx.governance.aichat.exception;

/**
 * 配额相关异常
 */
public class QuotaException extends AiChatException {

    public QuotaException(ErrorCode errorCode) {
        super(errorCode);
    }

    public QuotaException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public QuotaException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public static QuotaException exceeded(String customerId, int used, int limit) {
        return new QuotaException(
            ErrorCode.QUOTA_EXCEEDED,
            String.format("用户 %s 配额已用尽: %d/%d tokens", customerId, used, limit)
        );
    }

    public static QuotaException notFound(String customerId) {
        return new QuotaException(
            ErrorCode.QUOTA_EXCEEDED,
            "用户 " + customerId + " 的配额未配置"
        );
    }
}
