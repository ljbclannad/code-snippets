package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.application.governance.*;
import com.lx.governance.vectormemory.application.ingestion.*;
import com.lx.governance.vectormemory.application.retrieval.*;
import com.lx.governance.vectormemory.domain.candidate.*;
import com.lx.governance.vectormemory.domain.lifecycle.*;
import com.lx.governance.vectormemory.domain.retrieval.*;
import com.lx.governance.vectormemory.integration.ai.*;
import com.lx.governance.vectormemory.integration.storage.*;
import com.lx.governance.vectormemory.support.observability.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;

class MemoryConflictResolverTest {

    private MemoryConflictResolver resolver;
    private SemanticMemoryConflictDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SemanticMemoryConflictDetector();
        resolver = new MemoryConflictResolver(detector);
    }

    @Test
    void shouldResolvePreferenceConflictAsLatestWinsUpdate() {
        AiGroupMemoryCandidateEntity candidate = candidate("PREFERENCE", "用户喜欢重辣饮食");
        AiGroupMemoryEntity confirmed = confirmed("PREFERENCE", "用户喜欢清淡饮食");

        MemoryConflictResolution resolution = resolver.resolve(
                "PREFERENCE",
                detector.fingerprint("PREFERENCE", candidate.getMemoryContent()),
                candidate,
                confirmed);

        assertEquals(MemoryRelationType.CONFLICT, resolution.getRelationType());
        assertEquals(MemoryConflictResolution.ResolutionStrategy.UPDATE_EXISTING_PREFERENCE, resolution.getStrategy());
        assertEquals("preference_latest_wins", resolution.getReasonCode());
    }

    @Test
    void shouldResolveFactConflictAsVersionAppend() {
        AiGroupMemoryCandidateEntity candidate = candidate("MEDICATION", "用户服用二甲双胍");
        AiGroupMemoryEntity confirmed = confirmed("MEDICATION", "用户未服用二甲双胍");

        MemoryConflictResolution resolution = resolver.resolve(
                "MEDICATION",
                detector.fingerprint("MEDICATION", candidate.getMemoryContent()),
                candidate,
                confirmed);

        assertEquals(MemoryRelationType.CONFLICT, resolution.getRelationType());
        assertEquals(MemoryConflictResolution.ResolutionStrategy.APPEND_AS_VERSION, resolution.getStrategy());
        assertEquals("fact_version_append", resolution.getReasonCode());
    }

    @Test
    void shouldResolveSymptomConflictAsTimeWindowAppend() {
        AiGroupMemoryCandidateEntity candidate = candidate("SYMPTOM", "用户最近咳嗽");
        AiGroupMemoryEntity confirmed = confirmed("SYMPTOM", "用户没有咳嗽");

        MemoryConflictResolution resolution = resolver.resolve(
                "SYMPTOM",
                detector.fingerprint("SYMPTOM", candidate.getMemoryContent()),
                candidate,
                confirmed);

        assertEquals(MemoryRelationType.COEXIST, resolution.getRelationType());
        assertEquals(MemoryConflictResolution.ResolutionStrategy.APPEND_WITH_TIME_WINDOW, resolution.getStrategy());
        assertEquals("symptom_time_window_append", resolution.getReasonCode());
    }

    private AiGroupMemoryCandidateEntity candidate(String memoryType, String content) {
        AiGroupMemoryCandidateEntity entity = new AiGroupMemoryCandidateEntity();
        entity.setMemoryType(memoryType);
        entity.setMemoryContent(content);
        return entity;
    }

    private AiGroupMemoryEntity confirmed(String memoryType, String content) {
        AiGroupMemoryEntity entity = new AiGroupMemoryEntity();
        entity.setMemoryType(memoryType);
        entity.setMemoryContent(content);
        return entity;
    }
}
