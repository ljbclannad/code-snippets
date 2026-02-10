package com.lx.governance.common.web;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<?> badRequest(IllegalArgumentException e) {
        return ApiResponse.fail("BAD_REQUEST", e.getMessage(), Trace.getOrCreate());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<?> unexpected(Exception e) {
        return ApiResponse.fail("INTERNAL_ERROR", e.getMessage(), Trace.getOrCreate());
    }
}
