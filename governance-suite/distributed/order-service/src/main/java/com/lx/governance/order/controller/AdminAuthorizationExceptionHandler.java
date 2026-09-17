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
import com.lx.governance.order.export.FinanceExportService.FinanceExportConflictException;
import com.lx.governance.order.export.FinanceExportService.FinanceExportExpiredException;
import com.lx.governance.order.export.FinanceExportService.FinanceExportFinanceRoleRequiredException;
import com.lx.governance.order.export.FinanceExportService.FinanceExportNotFoundException;

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

    @ExceptionHandler(FinanceExportFinanceRoleRequiredException.class)
    public ResponseEntity<ApiResponse<?>> financeRoleRequired(FinanceExportFinanceRoleRequiredException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("FINANCE_VIEWER_REQUIRED", "finance viewer role is required", Trace.getOrCreate()));
    }

    @ExceptionHandler(FinanceExportNotFoundException.class)
    public ResponseEntity<ApiResponse<?>> exportNotFound(FinanceExportNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("EXPORT_NOT_FOUND", "export task not found", Trace.getOrCreate()));
    }

    @ExceptionHandler(FinanceExportExpiredException.class)
    public ResponseEntity<ApiResponse<?>> exportExpired(FinanceExportExpiredException exception) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiResponse.fail("EXPORT_EXPIRED", "export task has expired", Trace.getOrCreate()));
    }

    @ExceptionHandler(FinanceExportConflictException.class)
    public ResponseEntity<ApiResponse<?>> exportConflict(FinanceExportConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("EXPORT_CONFLICT", exception.getMessage(), Trace.getOrCreate()));
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
