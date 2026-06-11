package com.lx.governance.aichat.dto;

import com.lx.governance.aichat.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一错误响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private Integer code;
    private String message;
    private String detail;
    private Long timestamp;
    private String path;
    private String traceId;

    public static ErrorResponse of(ErrorCode errorCode) {
        return ErrorResponse.builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static ErrorResponse of(ErrorCode errorCode, String detail) {
        return ErrorResponse.builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .detail(detail)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static ErrorResponse of(int code, String message, String detail) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .detail(detail)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
