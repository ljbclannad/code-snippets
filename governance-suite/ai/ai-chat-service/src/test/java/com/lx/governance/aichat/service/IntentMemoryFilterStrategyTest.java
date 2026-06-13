package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.dto.SessionContext;

class IntentMemoryFilterStrategyTest {

    private final IntentMemoryFilterStrategy strategy = new IntentMemoryFilterStrategy();

    @Test
    void shouldUseMedicationStrategyForMedicationIntent() {
        SessionContext context = SessionContext.builder()
                .userIntent("用药建议")
                .currentTopic("降压药调整")
                .build();

        IntentMemoryFilterStrategy.MemoryFilterDecision decision = strategy.decide(context);

        assertEquals("medication", decision.getStrategyName());
        assertTrue(decision.getMemoryTypes().contains("MEDICATION"));
        assertTrue(Boolean.TRUE.equals(decision.getVerifiedOnly()));
        assertEquals(180, decision.getRecentDays());
    }

    @Test
    void shouldUseDietStrategyForDietIntent() {
        SessionContext context = SessionContext.builder()
                .userIntent("饮食建议")
                .currentTopic("控糖晚饭")
                .build();

        IntentMemoryFilterStrategy.MemoryFilterDecision decision = strategy.decide(context);

        assertEquals("diet", decision.getStrategyName());
        assertTrue(decision.getMemoryTypes().contains("DIET"));
        assertTrue(decision.getMemoryTypes().contains("PREFERENCE"));
        assertEquals(90, decision.getRecentDays());
    }

    @Test
    void shouldFallbackToDefaultStrategy() {
        SessionContext context = SessionContext.builder()
                .userIntent("一般咨询")
                .currentTopic("健康咨询")
                .build();

        IntentMemoryFilterStrategy.MemoryFilterDecision decision = strategy.decide(context);

        assertEquals("default", decision.getStrategyName());
        assertTrue(decision.getMemoryTypes().contains("OTHER"));
        assertEquals(90, decision.getRecentDays());
    }
}
