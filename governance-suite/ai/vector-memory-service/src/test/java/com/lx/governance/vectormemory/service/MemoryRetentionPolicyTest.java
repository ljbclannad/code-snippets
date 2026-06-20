package com.lx.governance.vectormemory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lx.governance.vectormemory.config.MemoryLifecycleProperties;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;

class MemoryRetentionPolicyTest {

    private MemoryRetentionPolicy retentionPolicy;

    @BeforeEach
    void setUp() {
        retentionPolicy = new MemoryRetentionPolicy(new MemoryLifecycleProperties());
    }

    @Test
    void shouldDecayExpiredSymptomMemory() {
        AiGroupMemoryEntity memory = new AiGroupMemoryEntity();
        memory.setMemoryType("SYMPTOM");
        memory.setLifecycleStatus("CONFIRMED");
        memory.setRetentionPolicy("TIME_WINDOW");
        memory.setExpiresAt(LocalDateTime.now().minusDays(1));

        MemoryRetentionPolicy.RetentionDecision decision = retentionPolicy.evaluate(memory, LocalDateTime.now());

        assertEquals("DECAY", decision.getAction());
        assertEquals("ttl_expired", decision.getReasonCode());
    }

    @Test
    void shouldArchiveLowValueDecayingMemory() {
        AiGroupMemoryEntity memory = new AiGroupMemoryEntity();
        memory.setMemoryType("PREFERENCE");
        memory.setLifecycleStatus("DECAYING");
        memory.setRetentionPolicy("LATEST_WINS");
        memory.setLastReinforcedAt(LocalDateTime.now().minusDays(220));
        memory.setUpdateTime(LocalDateTime.now().minusDays(40));

        MemoryRetentionPolicy.RetentionDecision decision = retentionPolicy.evaluate(memory, LocalDateTime.now());

        assertEquals("ARCHIVE", decision.getAction());
        assertEquals("low_value_archived", decision.getReasonCode());
    }

    @Test
    void shouldDeleteArchivedMemoryAfterRetentionWindow() {
        AiGroupMemoryEntity memory = new AiGroupMemoryEntity();
        memory.setMemoryType("OTHER");
        memory.setLifecycleStatus("ARCHIVED");
        memory.setArchivedAt(LocalDateTime.now().minusDays(100));

        MemoryRetentionPolicy.RetentionDecision decision = retentionPolicy.evaluate(memory, LocalDateTime.now());

        assertEquals("DELETE", decision.getAction());
        assertEquals("archive_retention_expired", decision.getReasonCode());
    }
}
