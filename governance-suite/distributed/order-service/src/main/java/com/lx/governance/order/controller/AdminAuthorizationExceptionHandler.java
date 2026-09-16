package com.lx.governance.order.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.adminauth.AdminAuthenticationException;
import com.lx.governance.order.adminauth.AdminRoleRequiredException;

@RestControllerAdvice(basePackages = "com.lx.governance.order.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminAuthorizationExceptionHandler {
    @ExceptionHandler(AdminAuthenticationException.class)
    public ResponseEntity<ApiResponse<?>> unauthorized(AdminAuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail("UNAUTHORIZED", exception.getMessage(), Trace.getOrCreate()));
    }

    @ExceptionHandler(AdminRoleRequiredException.class)
    public ResponseEntity<ApiResponse<?>> roleRequired(AdminRoleRequiredException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("ADMIN_ROLE_REQUIRED", exception.getMessage(), Trace.getOrCreate()));
    }
}
