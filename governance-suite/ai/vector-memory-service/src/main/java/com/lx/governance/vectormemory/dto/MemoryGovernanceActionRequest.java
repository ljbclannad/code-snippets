package com.lx.governance.vectormemory.dto;

import lombok.Data;

@Data
public class MemoryGovernanceActionRequest {

    private String reviewer = "SYSTEM";
    private String reasonCode;
    private String remarks;
}
