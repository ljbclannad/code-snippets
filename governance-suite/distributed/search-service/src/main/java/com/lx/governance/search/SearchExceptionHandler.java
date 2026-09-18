package com.lx.governance.search;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SearchExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<?>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("BAD_REQUEST", exception.getMessage(), Trace.getOrCreate()));
    }

    @ExceptionHandler(SearchUnavailableException.class)
    ResponseEntity<ApiResponse<?>> unavailable(SearchUnavailableException exception) {
        return ResponseEntity.status(503).body(ApiResponse.fail("SEARCH_UNAVAILABLE", exception.getMessage(), Trace.getOrCreate()));
    }
}
