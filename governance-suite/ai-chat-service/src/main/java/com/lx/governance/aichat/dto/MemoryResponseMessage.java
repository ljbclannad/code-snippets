package com.lx.governance.aichat.dto;

import lombok.Data;
import java.util.List;

@Data
public class MemoryResponseMessage {
    private String correlationId;
    private Boolean success;
    private String errorMessage;
    private List<MemoryItemDTO> data;
    private Long timestamp;
}
