package com.lx.governance.vectormemory.dto;

import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemorySearchFilter {

    private Set<String> memoryTypes;
    private Double minConfidence;
    private Boolean verifiedOnly;
    private Integer recentDays;
}
