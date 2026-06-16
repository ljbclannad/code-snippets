package com.lx.governance.aichat.dto;

import java.util.Set;

import lombok.Data;

@Data
public class MemoryRequestMessage {
    private String correlationId;
    private String groupId;
    private String customerId;
    private String query;
    private Integer topK;
    private Long timestamp;
    private Set<String> memoryTypes;
    private Double minConfidence;
    private Boolean verifiedOnly;
    private Integer recentDays;
    private StructuredMemoryQuery structuredQuery;
}
