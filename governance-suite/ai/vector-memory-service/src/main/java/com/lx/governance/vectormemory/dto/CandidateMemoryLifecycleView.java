package com.lx.governance.vectormemory.dto;

import java.util.List;

import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryConflictEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryLifecycleEventEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryReviewAuditEntity;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CandidateMemoryLifecycleView {

    AiGroupMemoryCandidateEntity candidate;
    List<AiGroupMemoryConflictEntity> conflicts;
    List<AiGroupMemoryReviewAuditEntity> audits;
    List<AiGroupMemoryLifecycleEventEntity> events;
    AiGroupMemoryEntity relatedFormalMemory;
}
