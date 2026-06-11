package com.lx.governance.aichat.exception;

/**
 * 记忆服务相关异常
 */
public class MemoryServiceException extends AiChatException {

    public MemoryServiceException(ErrorCode errorCode) {
        super(errorCode);
    }

    public MemoryServiceException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public MemoryServiceException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public static MemoryServiceException fetchError(Throwable cause) {
        return new MemoryServiceException(ErrorCode.MEMORY_FETCH_ERROR, cause);
    }

    public static MemoryServiceException saveError(Throwable cause) {
        return new MemoryServiceException(ErrorCode.MEMORY_SAVE_ERROR, cause);
    }

    public static MemoryServiceException vectorSearchError(Throwable cause) {
        return new MemoryServiceException(ErrorCode.VECTOR_SEARCH_ERROR, cause);
    }
}
