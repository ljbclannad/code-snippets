package com.lx.governance.common.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    public GlobalExceptionHandler() {
        log.info("========== GlobalExceptionHandler 已创建 ==========");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<?> badRequest(IllegalArgumentException e) {
        log.warn("[EXCEPTION] IllegalArgumentException: {}", e.getMessage());
        return ApiResponse.fail("BAD_REQUEST", e.getMessage(), Trace.getOrCreate());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ApiResponse<?> businessError(IllegalStateException e) {
        log.warn("[EXCEPTION] IllegalStateException: {}", e.getMessage());
        return ApiResponse.fail("BUSINESS_ERROR", e.getMessage(), Trace.getOrCreate());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<?> unexpected(Exception e) {
        log.error("[EXCEPTION] Exception: {}", e.getMessage(), e);
        return ApiResponse.fail("INTERNAL_ERROR", e.getMessage(), Trace.getOrCreate());
    }
}
