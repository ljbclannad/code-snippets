package com.lx.governance.vectormemory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemanticMemoryConflictDetectorTest {

    private final SemanticMemoryConflictDetector detector = new SemanticMemoryConflictDetector();

    @Test
    void shouldNormalizeSameSemanticPreferenceIntoSameFingerprint() {
        SemanticMemoryFingerprint left = detector.fingerprint("PREFERENCE", "用户喜欢清淡饮食");
        SemanticMemoryFingerprint right = detector.fingerprint("PREFERENCE", "用户偏好清淡的饮食");

        assertEquals(left.getNormalizedKey(), right.getNormalizedKey());
        assertEquals(left.getNormalizedValue(), right.getNormalizedValue());
        assertEquals("POSITIVE", left.getPolarity());
    }

    @Test
    void shouldDetectPolarityOppositionForSameSubject() {
        SemanticMemoryFingerprint positive = detector.fingerprint("PREFERENCE", "用户喜欢吃辣");
        SemanticMemoryFingerprint negative = detector.fingerprint("PREFERENCE", "用户不吃辣");

        SemanticConflictAnalysis analysis = detector.analyze(positive, negative);

        assertTrue(analysis.isConflict());
        assertEquals("POLARITY_OPPOSITION", analysis.getConflictType());
        assertEquals("semantic_polarity_opposition", analysis.getConflictReason());
    }

    @Test
    void shouldDetectValueMismatchForDietTastePreference() {
        SemanticMemoryFingerprint mild = detector.fingerprint("PREFERENCE", "用户喜欢清淡饮食");
        SemanticMemoryFingerprint spicy = detector.fingerprint("PREFERENCE", "用户喜欢重辣饮食");

        SemanticConflictAnalysis analysis = detector.analyze(mild, spicy);

        assertTrue(analysis.isConflict());
        assertEquals("VALUE_MISMATCH", analysis.getConflictType());
        assertEquals("semantic_value_mismatch", analysis.getConflictReason());
    }

    @Test
    void shouldNotTreatDifferentAllergySubjectsAsConflict() {
        SemanticMemoryFingerprint seafood = detector.fingerprint("SYMPTOM", "用户对海鲜过敏");
        SemanticMemoryFingerprint peanut = detector.fingerprint("SYMPTOM", "用户对花生过敏");

        SemanticConflictAnalysis analysis = detector.analyze(seafood, peanut);

        assertEquals("NONE", analysis.getConflictType());
        assertTrue(!analysis.isConflict());
    }
}
