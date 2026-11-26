package com.health.agent.service.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.test.util.ReflectionTestUtils;
import com.health.agent.config.DoctorSafetyProperties;

import java.time.LocalDate;
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
        LocalDate today = LocalDate.now();
        Map<String, Object> result = weightPlateauService.detectPlateau(List.of(
            Map.of("date", today.toString(), "weight", 80.0),
            Map.of("date", today.minusDays(2).toString(), "weight", 80.1),
            Map.of("date", today.minusDays(5).toString(), "weight", 79.9),
            Map.of("date", today.minusDays(8).toString(), "weight", 80.2),
            Map.of("date", today.minusDays(11).toString(), "weight", 80.1),
            Map.of("date", today.minusDays(14).toString(), "weight", 80.3)
        ));

        assertTrue((Boolean) result.get("isPlateau"));
        assertEquals("PLATEAU", result.get("recentTrend"));
    }

    @Test
    void shouldNotDetectPlateauWhenRecentDownwardTrendIsStillMeaningful() {
        LocalDate today = LocalDate.now();
        Map<String, Object> result = weightPlateauService.detectPlateau(List.of(
            Map.of("date", today.toString(), "weight", 79.0),
            Map.of("date", today.minusDays(2).toString(), "weight", 79.3),
            Map.of("date", today.minusDays(5).toString(), "weight", 79.5),
            Map.of("date", today.minusDays(8).toString(), "weight", 79.8),
            Map.of("date", today.minusDays(11).toString(), "weight", 80.1),
            Map.of("date", today.minusDays(14).toString(), "weight", 80.4)
        ));

        assertFalse((Boolean) result.get("isPlateau"));
        assertEquals("DOWNWARD", result.get("recentTrend"));
    }

    @Test
    void shouldIncludeTodayAndThirteenDaysAgoButExcludeOlderAndFutureRecords() {
        LocalDate today = LocalDate.now();
        Map<String, Object> result = weightPlateauService.detectPlateau(List.of(
            Map.of("date", today.plusDays(1).toString(), "weight", 120.0),
            Map.of("date", today.minusDays(14).toString(), "weight", 90.0),
            Map.of("date", today.minusDays(13).toString(), "weight", 81.0),
            Map.of("date", today.minusDays(6).toString(), "weight", 80.1),
            Map.of("date", today.minusDays(3).toString(), "weight", 80.1),
            Map.of("date", today.toString(), "weight", 80.0)
        ));

        assertEquals(-1.0d, result.get("weightChangeKg"));
        assertEquals(-0.1d, result.get("recentChangeKg"));
        assertEquals("NO_PLATEAU", result.get("reasonCode"));
    }

    @Test
    void shouldUseSparseRecordsWithinWindowWithoutRequiringFourteenDayCoverage() {
        LocalDate today = LocalDate.now();
        Map<String, Object> result = weightPlateauService.detectPlateau(List.of(
            Map.of("date", today.toString(), "weight", 80.0),
            Map.of("date", today.minusDays(3).toString(), "weight", 80.1),
            Map.of("date", today.minusDays(6).toString(), "weight", 80.1)
        ));

        assertTrue((Boolean) result.get("isPlateau"));
        assertEquals("PLATEAU", result.get("reasonCode"));
        assertEquals(-0.1d, result.get("weightChangeKg"));
    }

    @Test
    void shouldAnchorRecentTrendToTodayEvenWhenLatestRecordIsOlder() {
        LocalDate today = LocalDate.now();
        Map<String, Object> result = weightPlateauService.detectPlateau(List.of(
            Map.of("date", today.minusDays(13).toString(), "weight", 80.1),
            Map.of("date", today.minusDays(8).toString(), "weight", 90.0),
            Map.of("date", today.minusDays(6).toString(), "weight", 80.1),
            Map.of("date", today.minusDays(2).toString(), "weight", 80.0)
        ));

        assertEquals("PLATEAU", result.get("reasonCode"));
        assertEquals(-0.1d, result.get("recentChangeKg"));
    }

    @ParameterizedTest
    @CsvSource({"30,16", "13,8", "13,0"})
    void shouldRequireAtLeastTwoRecordsInBothWindows(int firstDaysAgo, int lastDaysAgo) {
        LocalDate today = LocalDate.now();
        Map<String, Object> result = weightPlateauService.detectPlateau(List.of(
            Map.of("date", today.minusDays(firstDaysAgo).toString(), "weight", 80.1),
            Map.of("date", today.minusDays(lastDaysAgo).toString(), "weight", 80.0)
        ));

        assertFalse((Boolean) result.get("isPlateau"));
        assertEquals("INSUFFICIENT_DATA", result.get("reasonCode"));
        assertEquals("UNKNOWN", result.get("recentTrend"));
        assertTrue(result.get("reason").toString().contains("记录不足两条"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldReturnInsufficientDataWithoutRecords(List<Map<String, Object>> records) {
        assertEquals("INSUFFICIENT_DATA", weightPlateauService.detectPlateau(records).get("reasonCode"));
    }

    @Test
    void shouldApplyConfiguredWindowLengths() {
        var properties = new DoctorSafetyProperties();
        properties.getWeightPlateau().setWindowDays(5);
        properties.getWeightPlateau().setRecentTrendDays(3);
        var service = new WeightPlateauService(properties);
        LocalDate today = LocalDate.now();
        Map<String, Object> result = service.detectPlateau(List.of(
            Map.of("date", today.minusDays(5).toString(), "weight", 95.0),
            Map.of("date", today.minusDays(4).toString(), "weight", 81.0),
            Map.of("date", today.minusDays(2).toString(), "weight", 80.1),
            Map.of("date", today.toString(), "weight", 80.0)
        ));

        assertEquals(5, result.get("windowDays"));
        assertEquals(-1.0d, result.get("weightChangeKg"));
        assertEquals(-0.1d, result.get("recentChangeKg"));
        assertEquals("NO_PLATEAU", result.get("reasonCode"));
    }
}
