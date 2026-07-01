package com.lx.governance.vectormemory.entity;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class AiGroupMemoryLifecycleEventEntity {

    private Long id;
    private String entityType;
    private Long entityId;
    private String customerId;
    private String groupId;
    private String memoryType;
    private String eventType;
    private String lifecycleStatus;
    private String reasonCode;
    private String eventPayload;
    private LocalDateTime createTime;
}
