package com.lx.governance.aichat.exception;

import com.lx.governance.aichat.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Void> handleAccessError(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).build();
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(AiChatException.class)
    public ResponseEntity<ErrorResponse> handleAiChatException(AiChatException ex) {
        log.error("业务异常: code={}, message={}, detail={}",
                ex.getErrorCode().getCode(),
                ex.getErrorCode().getMessage(),
                ex.getDetail(),
                ex);

        ErrorResponse response = ErrorResponse.of(
                ex.getErrorCode(),
                ex.getDetail()
        );
        response.setPath(getCurrentRequestPath());

        // 根据错误类型返回不同的 HTTP 状态码
        HttpStatus httpStatus = mapToHttpStatus(ex.getErrorCode());

        return ResponseEntity.status(httpStatus).body(response);
    }

    /**
     * 处理参数验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        log.warn("参数验证失败: {}", detail);

        ErrorResponse response = ErrorResponse.of(
                ErrorCode.INVALID_PARAMETER,
                detail
        );
        response.setPath(getCurrentRequestPath());

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 处理约束验证异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));

        log.warn("约束验证失败: {}", detail);

        ErrorResponse response = ErrorResponse.of(
                ErrorCode.INVALID_PARAMETER,
                detail
        );
        response.setPath(getCurrentRequestPath());

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("非法参数: {}", ex.getMessage());

        ErrorResponse response = ErrorResponse.of(
                ErrorCode.INVALID_PARAMETER,
                ex.getMessage()
        );
        response.setPath(getCurrentRequestPath());

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 处理未知异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        log.error("未知异常", ex);

        ErrorResponse response = ErrorResponse.of(
                ErrorCode.UNKNOWN_ERROR,
                "系统内部错误，请稍后重试"
        );
        response.setPath(getCurrentRequestPath());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 将错误码映射到 HTTP 状态码
     */
    private HttpStatus mapToHttpStatus(ErrorCode errorCode) {
        int code = errorCode.getCode();

        // 参数错误
        if (code >= 1001 && code < 1100) {
            return HttpStatus.BAD_REQUEST;
        }

        // 服务不可用
        if (code >= 1002 && code < 1004) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }

        // 对话繁忙
        if (code == 2001) {
            return HttpStatus.CONFLICT;
        }

        // 资源不存在
        if (code == 2002 || code == 4001) {
            return HttpStatus.NOT_FOUND;
        }

        // 请求内容错误
        if (code >= 2003 && code < 2100) {
            return HttpStatus.BAD_REQUEST;
        }

        // AI 服务错误
        if (code >= 3000 && code < 4000) {
            return HttpStatus.BAD_GATEWAY;
        }

        // 限流和配额
        if (code >= 7000 && code < 8000) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }

        // 默认返回 500
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * 获取当前请求路径
     */
    private String getCurrentRequestPath() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                return request.getRequestURI();
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }
}
