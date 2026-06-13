package com.lx.governance.aichat.exception;

import lombok.Getter;

/**
 * AI Chat 业务异常基类
 */
@Getter
public class AiChatException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;

    public AiChatException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = null;
    }

    public AiChatException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + ": " + detail);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public AiChatException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.detail = cause.getMessage();
    }

    public AiChatException(ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.getMessage() + ": " + detail, cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }
}
