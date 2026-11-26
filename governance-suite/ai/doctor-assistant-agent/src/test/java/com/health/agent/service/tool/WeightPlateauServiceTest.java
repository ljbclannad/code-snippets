package com.health.agent.service.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightPlateauServiceTest {

    private WeightPlateauService weightPlateauService;

    @BeforeEach
    void setUp() {
        weightPlateauService = new WeightPlateauService();
        ReflectionTestUtils.setField(weightPlateauService, "windowDays", 14);
        ReflectionTestUtils.setField(weightPlateauService, "maxWeightChangeKg", 0.5d);
        ReflectionTestUtils.setField(weightPlateauService, "recentTrendDays", 7);
        ReflectionTestUtils.setField(weightPlateauService, "recentDownwardThresholdKg", 0.2d);
    }

    @Test
    void shouldDetectPlateauWhenTwoWeekWeightChangeIsSmallAndRecentTrendIsFlat() {
        Map<String, Object> result = weightPlateauService.detectPlateau(List.of(
            Map.of("date", "2026-05-30", "weight", 80.0),
            Map.of("date", "2026-05-28", "weight", 80.1),
            Map.of("date", "2026-05-25", "weight", 79.9),
            Map.of("date", "2026-05-22", "weight", 80.2),
            Map.of("date", "2026-05-19", "weight", 80.1),
            Map.of("date", "2026-05-16", "weight", 80.3)
        ));

        assertTrue((Boolean) result.get("isPlateau"));
        assertEquals("PLATEAU", result.get("recentTrend"));
    }

    @Test
    void shouldNotDetectPlateauWhenRecentDownwardTrendIsStillMeaningful() {
        Map<String, Object> result = weightPlateauService.detectPlateau(List.of(
            Map.of("date", "2026-05-30", "weight", 79.0),
            Map.of("date", "2026-05-28", "weight", 79.3),
            Map.of("date", "2026-05-25", "weight", 79.5),
            Map.of("date", "2026-05-22", "weight", 79.8),
            Map.of("date", "2026-05-19", "weight", 80.1),
            Map.of("date", "2026-05-16", "weight", 80.4)
        ));

        assertFalse((Boolean) result.get("isPlateau"));
        assertEquals("DOWNWARD", result.get("recentTrend"));
    }

    @Test
    void shouldReturnInsufficientDataWhenWindowCoverageIsTooShort() {
        Map<String, Object> result = weightPlateauService.detectPlateau(List.of(
            Map.of("date", "2026-05-30", "weight", 80.0),
            Map.of("date", "2026-05-27", "weight", 80.1),
            Map.of("date", "2026-05-24", "weight", 80.2)
        ));

        assertFalse((Boolean) result.get("isPlateau"));
        assertEquals("INSUFFICIENT_DATA", result.get("reasonCode"));
    }
}
