package com.lx.governance.vectormemory.dto;

import lombok.Data;

@Data
public class MemoryGovernanceMergeRequest {

    private Long sourceMemoryId;
    private Long targetMemoryId;
    private String reviewer = "SYSTEM";
    private String reasonCode = "manual_merge";
    private String remarks;
}
