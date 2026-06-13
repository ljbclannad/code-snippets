package com.health.agent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 体重停滞期识别服务
 */
@Service
public class WeightPlateauService {

    @Value("${agent.weight-plateau.window-days:14}")
    private int windowDays;

    @Value("${agent.weight-plateau.max-weight-change-kg:0.5}")
    private double maxWeightChangeKg;

    @Value("${agent.weight-plateau.recent-trend-days:7}")
    private int recentTrendDays;

    @Value("${agent.weight-plateau.recent-downward-threshold-kg:0.2}")
    private double recentDownwardThresholdKg;

    public Map<String, Object> detectPlateau(List<Map<String, Object>> weightRecords) {
        List<RecordPoint> points = normalize(weightRecords);
        if (points.size() < 2) {
            return insufficientData();
        }

        points.sort(Comparator.comparing(RecordPoint::date));
        RecordPoint earliest = points.get(0);
        RecordPoint latest = points.get(points.size() - 1);
        long coveredDays = latest.date().toEpochDay() - earliest.date().toEpochDay();
        if (coveredDays < windowDays) {
            return insufficientData();
        }

        double totalChange = latest.weight() - earliest.weight();
        LocalDate recentStart = latest.date().minusDays(recentTrendDays - 1L);
        List<RecordPoint> recentPoints = new ArrayList<>();
        for (RecordPoint point : points) {
            if (!point.date().isBefore(recentStart)) {
                recentPoints.add(point);
            }
        }
        if (recentPoints.size() < 2) {
            return insufficientData();
        }

        double recentChange = recentPoints.get(recentPoints.size() - 1).weight() - recentPoints.get(0).weight();
        String recentTrend = classifyRecentTrend(recentChange);
        boolean plateau = Math.abs(totalChange) < maxWeightChangeKg && !"DOWNWARD".equals(recentTrend);

        return Map.of(
            "isPlateau", plateau,
            "windowDays", windowDays,
            "weightChangeKg", round(totalChange),
            "recentTrend", recentTrend,
            "recentChangeKg", round(recentChange),
            "reasonCode", plateau ? "PLATEAU" : "NO_PLATEAU",
            "reason", plateau
                ? "近两周体重变化较小，且最近一周未出现明显下降趋势，可判定为减重停滞期。"
                : "最近一周仍存在下降趋势或整体变化超过停滞阈值，暂不判定为停滞期。",
            "evidence", List.of(
                "近%d天体重变化 %.1fkg".formatted(windowDays, round(totalChange)),
                "最近%d天趋势为 %s".formatted(recentTrendDays, recentTrend)
            ),
            "evidenceSources", List.of("WEIGHT_HISTORY")
        );
    }

    private String classifyRecentTrend(double recentChange) {
        if (recentChange < -recentDownwardThresholdKg) {
            return "DOWNWARD";
        }
        if (recentChange > recentDownwardThresholdKg) {
            return "UPWARD";
        }
        return "PLATEAU";
    }

    private Map<String, Object> insufficientData() {
        return Map.of(
            "isPlateau", false,
            "windowDays", windowDays,
            "weightChangeKg", 0.0,
            "recentTrend", "UNKNOWN",
            "recentChangeKg", 0.0,
            "reasonCode", "INSUFFICIENT_DATA",
            "reason", "体重记录覆盖时长不足，无法判断是否进入停滞期。"
        );
    }

    private List<RecordPoint> normalize(List<Map<String, Object>> weightRecords) {
        List<RecordPoint> points = new ArrayList<>();
        if (weightRecords == null) {
            return points;
        }

        for (Map<String, Object> record : weightRecords) {
            Object date = record.get("date");
            Object weight = record.get("weight");
            if (date == null || weight == null) {
                continue;
            }
            points.add(new RecordPoint(
                LocalDate.parse(String.valueOf(date)),
                ((Number) weight).doubleValue()
            ));
        }
        return points;
    }

    private double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private record RecordPoint(LocalDate date, double weight) {
    }
}
