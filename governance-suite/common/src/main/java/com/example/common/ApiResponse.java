package com.example.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private String code;
    private String message;
    private String traceId;
    private T data;

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        ApiResponse<T> r = new ApiResponse<>();
        r.success = true;
        r.code = "OK";
        r.message = "ok";
        r.traceId = traceId;
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> fail(String code, String message, String traceId) {
        ApiResponse<T> r = new ApiResponse<>();
        r.success = false;
        r.code = code;
        r.message = message;
        r.traceId = traceId;
        return r;
    }

    public boolean isSuccess() { return success; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getTraceId() { return traceId; }
    public T getData() { return data; }
}
