package com.health.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

    @Test
    void shouldReturnBadRequestForValidationFailure() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "doctorId", "doctorId 不能为空"));
        MethodParameter parameter = new MethodParameter(
            SampleController.class.getDeclaredMethod("sample", String.class),
            0
        );
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
            handler.handleMethodArgumentNotValid(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().getCode());
        assertEquals("doctorId 不能为空", response.getBody().getMessage());
    }

    static class SampleController {
        @SuppressWarnings("unused")
        public void sample(String body) {
        }
    }
}
