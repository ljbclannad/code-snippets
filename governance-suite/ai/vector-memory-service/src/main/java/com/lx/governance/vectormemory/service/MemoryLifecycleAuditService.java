package com.lx.governance.vectormemory.service;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.dto.MemoryLifecycleDecision;
import com.lx.governance.vectormemory.entity.AiGroupMemoryLifecycleEventEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryLifecycleEventMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryLifecycleAuditService {

    private final AiGroupMemoryLifecycleEventMapper lifecycleEventMapper;

    public void recordIngestionDecision(MemoryLifecycleDecision decision) {
        if (decision == null) {
            return;
        }
        log.info("memory lifecycle decision - groupId: {}, customerId: {}, action: {}, lifecycleStatus: {}, reasonCode: {}, score: {}, summary: {}",
                decision.getGroupId(),
                decision.getCustomerId(),
                decision.getAction(),
                decision.getLifecycleStatus(),
                decision.getDecisionReasonCode(),
                decision.getWorthinessScore(),
                decision.getDecisionSummary());
        insertEvent("CANDIDATE", null, decision.getCustomerId(), decision.getGroupId(),
                decision.getNormalizedMemoryType(), "INGESTION_DECISION",
                decision.getLifecycleStatus(), decision.getDecisionReasonCode(), decision.getDecisionSummary());
    }

    public void recordStateEvent(String entityType,
                                 Long entityId,
                                 String customerId,
                                 String groupId,
                                 String memoryType,
                                 String eventType,
                                 String lifecycleStatus,
                                 String reasonCode,
                                 String payload) {
        insertEvent(entityType, entityId, customerId, groupId, memoryType, eventType, lifecycleStatus, reasonCode, payload);
    }

    private void insertEvent(String entityType,
                             Long entityId,
                             String customerId,
                             String groupId,
                             String memoryType,
                             String eventType,
                             String lifecycleStatus,
                             String reasonCode,
                             String payload) {
        if (lifecycleEventMapper == null) {
            return;
        }
        AiGroupMemoryLifecycleEventEntity event = new AiGroupMemoryLifecycleEventEntity();
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setCustomerId(customerId);
        event.setGroupId(groupId);
        event.setMemoryType(memoryType);
        event.setEventType(eventType);
        event.setLifecycleStatus(lifecycleStatus);
        event.setReasonCode(reasonCode);
        event.setEventPayload(payload);
        lifecycleEventMapper.insert(event);
    }
}
