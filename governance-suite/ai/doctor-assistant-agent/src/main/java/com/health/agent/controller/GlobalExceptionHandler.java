package com.health.agent.controller;

import com.health.agent.exception.AgentException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理 AgentException
     */
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<ErrorResponse> handleAgentException(AgentException e) {
        log.error("Agent异常: {}", e.getMessage(), e);

        ErrorResponse response = ErrorResponse.builder()
            .code("AGENT_ERROR")
            .message(e.getMessage())
            .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 处理静态资源未找到，避免将浏览器默认请求误记为系统异常
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("静态资源不存在: {} {}", e.getHttpMethod(), e.getResourcePath());

        ErrorResponse response = ErrorResponse.builder()
            .code("NOT_FOUND")
            .message("请求资源不存在: " + e.getResourcePath())
            .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * 处理请求参数校验失败
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = fieldError != null ? fieldError.getDefaultMessage() : "请求参数校验失败";

        ErrorResponse response = ErrorResponse.builder()
            .code("VALIDATION_ERROR")
            .message(message)
            .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 处理通用异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("系统异常", e);

        ErrorResponse response = ErrorResponse.builder()
            .code("INTERNAL_ERROR")
            .message("系统内部错误: " + e.getMessage())
            .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 错误响应
     */
    @Data
    @lombok.Builder
    public static class ErrorResponse {
        private String code;
        private String message;
    }
}
