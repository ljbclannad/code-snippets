package com.lx.governance.vectormemory.dto;

import java.util.List;

import com.lx.governance.vectormemory.entity.AiGroupMemoryLifecycleEventEntity;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MemoryLifecycleReplayView {

    String customerId;
    String groupId;
    String memoryType;
    List<AiGroupMemoryLifecycleEventEntity> events;
}
