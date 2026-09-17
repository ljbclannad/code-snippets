package com.lx.governance.order.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.adminauth.AdminAuthenticationException;
import com.lx.governance.order.adminauth.AdminRoleRequiredException;
import com.lx.governance.order.adminauth.OrderOperatorRequiredException;

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

    @ExceptionHandler(OrderOperatorRequiredException.class)
    public ResponseEntity<ApiResponse<?>> orderOperatorRequired(OrderOperatorRequiredException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("ORDER_OPERATOR_REQUIRED", exception.getMessage(), Trace.getOrCreate()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("BAD_REQUEST", exception.getMessage(), Trace.getOrCreate()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> invalidRequestParameter(MethodArgumentTypeMismatchException ignored) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("BAD_REQUEST", "invalid request parameter", Trace.getOrCreate()));
    }
}
