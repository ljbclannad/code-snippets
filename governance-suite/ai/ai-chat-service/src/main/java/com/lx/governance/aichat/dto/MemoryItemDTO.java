package com.lx.governance.aichat.dto;

import lombok.Data;

/**
 * Memory item DTO (reused from vector-memory-service)
 */
@Data
public class MemoryItemDTO {

    private Long id;
    private String content;
    private Double similarity;
    private Double confidenceScore;
    private Boolean verified;
    private String source;
}
