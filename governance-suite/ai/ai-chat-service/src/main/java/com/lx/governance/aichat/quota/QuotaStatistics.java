package com.lx.governance.aichat.quota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 配额统计
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaStatistics {

    private String customerId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer totalTokens;
    private Integer totalPromptTokens;
    private Integer totalCompletionTokens;
    private Integer requestCount;
}
