package com.health.agent.service.tool;

import org.springframework.stereotype.Service;

import com.health.agent.config.DoctorSafetyProperties;

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

    private int windowDays = 14;

    private double maxWeightChangeKg = 0.5d;

    private int recentTrendDays = 7;

    private double recentDownwardThresholdKg = 0.2d;

    public WeightPlateauService() {
    }

    public WeightPlateauService(DoctorSafetyProperties properties) {
        applyProperties(properties);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void applyProperties(DoctorSafetyProperties properties) {
        if (properties == null || properties.getWeightPlateau() == null) {
            return;
        }
        DoctorSafetyProperties.WeightPlateau weightPlateau = properties.getWeightPlateau();
        this.windowDays = weightPlateau.getWindowDays();
        this.maxWeightChangeKg = weightPlateau.getMaxWeightChangeKg();
        this.recentTrendDays = weightPlateau.getRecentTrendDays();
        this.recentDownwardThresholdKg = weightPlateau.getRecentDownwardThresholdKg();
    }

    public Map<String, Object> detectPlateau(List<Map<String, Object>> weightRecords) {
        LocalDate today = LocalDate.now();
        LocalDate windowStart = today.minusDays(windowDays - 1L);
        List<RecordPoint> points = normalize(weightRecords);
        // 窗口包含今天及此前指定天数，允许缺测，但不使用窗口外或未来的记录。
        points.removeIf(point -> point.date().isBefore(windowStart) || point.date().isAfter(today));
        if (points.size() < 2) {
            return insufficientData();
        }

        points.sort(Comparator.comparing(RecordPoint::date));
        RecordPoint earliest = points.get(0);
        RecordPoint latest = points.get(points.size() - 1);
        double totalChange = latest.weight() - earliest.weight();
        LocalDate recentStart = today.minusDays(recentTrendDays - 1L);
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
                ? "近%d天窗口内体重变化较小，且最近%d天未出现明显下降趋势，可判定为减重停滞期。"
                    .formatted(windowDays, recentTrendDays)
                : "最近%d天仍存在下降趋势或近%d天窗口内体重变化达到停滞阈值，暂不判定为停滞期。"
                    .formatted(recentTrendDays, windowDays),
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
            "reason", "近%d天窗口或其中最近%d天的有效体重记录不足两条，无法判断是否进入停滞期。"
                .formatted(windowDays, recentTrendDays)
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
