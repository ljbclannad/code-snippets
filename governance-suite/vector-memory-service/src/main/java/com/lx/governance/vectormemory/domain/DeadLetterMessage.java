package com.lx.governance.vectormemory.domain;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 死信消息实体
 */
@Data
public class DeadLetterMessage {
    private Long id;
    private String exchange;
    private String routingKey;
    private String messageBody;
    private String failureReason;
    private Integer retryCount;
    private String status; // PENDING, RETRYING, RESOLVED, FAILED
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastRetryAt;
}
