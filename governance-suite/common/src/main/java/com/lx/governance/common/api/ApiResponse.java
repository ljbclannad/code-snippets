package com.lx.governance.common.api;

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
        r.message = "success";
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

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
