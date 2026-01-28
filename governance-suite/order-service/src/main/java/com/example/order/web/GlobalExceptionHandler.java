package com.example.order.web;

import com.example.common.ApiResponse;
import com.example.common.Trace;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> badRequest(MethodArgumentNotValidException e) {
        String traceId = Trace.getOrCreate();
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("invalid request");
        return ApiResponse.fail("BAD_REQUEST", msg, traceId);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> error(Exception e) {
        String traceId = Trace.getOrCreate();
        return ApiResponse.fail("INTERNAL_ERROR", e.getMessage(), traceId);
    }
}
