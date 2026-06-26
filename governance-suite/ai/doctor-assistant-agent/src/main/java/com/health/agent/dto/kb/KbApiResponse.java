package com.health.agent.dto.kb;

import lombok.Data;

@Data
public class KbApiResponse<T> {

    private boolean success;
    private String code;
    private String message;
    private String traceId;
    private T data;
}
