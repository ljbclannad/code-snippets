package com.health.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldReturnNotFoundForMissingStaticResource() {
        NoResourceFoundException exception = new NoResourceFoundException(HttpMethod.GET, "favicon.ico");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
            handler.handleNoResourceFound(exception);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("NOT_FOUND", response.getBody().getCode());
        assertEquals("请求资源不存在: favicon.ico", response.getBody().getMessage());
    }
}
