package com.lx.governance.profile.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemanticProfileConflictDetectorTest {

    private final SemanticProfileConflictDetector detector = new SemanticProfileConflictDetector();

    @Test
    void shouldNormalizeGenderVariants() {
        SemanticProfileFingerprint left = detector.fingerprint("basic.gender", "男性");
        SemanticProfileFingerprint right = detector.fingerprint("basic.gender", "男士");

        assertEquals("男", left.getNormalizedValue());
        assertEquals(left.getNormalizedValue(), right.getNormalizedValue());
    }

    @Test
    void shouldDetectDietPreferenceConflict() {
        SemanticProfileFingerprint mild = detector.fingerprint("behavior.dietPreference", "偏好清淡饮食");
        SemanticProfileFingerprint spicy = detector.fingerprint("behavior.dietPreference", "喜欢重辣饮食");

        SemanticProfileConflictAnalysis analysis = detector.analyze(mild, spicy);

        assertTrue(analysis.isConflict());
        assertEquals("VALUE_MISMATCH", analysis.getConflictType());
    }

    @Test
    void shouldDetectPolarityOpposition() {
        SemanticProfileFingerprint positive = detector.fingerprint("behavior.exerciseFrequency", "经常运动");
        SemanticProfileFingerprint negative = detector.fingerprint("behavior.exerciseFrequency", "很少运动");

        SemanticProfileConflictAnalysis analysis = detector.analyze(positive, negative);

        assertTrue(analysis.isConflict());
        assertEquals("POLARITY_OPPOSITION", analysis.getConflictType());
    }
}
