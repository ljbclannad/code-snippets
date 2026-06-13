package com.lx.governance.aichat.exception;

/**
 * 用户画像相关异常
 */
public class UserProfileException extends AiChatException {

    public UserProfileException(ErrorCode errorCode) {
        super(errorCode);
    }

    public UserProfileException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public UserProfileException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public static UserProfileException notFound(String customerId) {
        return new UserProfileException(
            ErrorCode.USER_PROFILE_NOT_FOUND,
            "用户 " + customerId + " 的画像不存在"
        );
    }

    public static UserProfileException fetchError(String customerId, Throwable cause) {
        return new UserProfileException(
            ErrorCode.USER_PROFILE_FETCH_ERROR,
            cause
        );
    }
}
