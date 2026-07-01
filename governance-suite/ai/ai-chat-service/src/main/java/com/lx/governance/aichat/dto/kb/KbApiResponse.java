package com.lx.governance.aichat.dto.kb;

import lombok.Data;

@Data
public class KbApiResponse<T> {
    private boolean success;
    private String code;
    private String message;
    private T data;
}
