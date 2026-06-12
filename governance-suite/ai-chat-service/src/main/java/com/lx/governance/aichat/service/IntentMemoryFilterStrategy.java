package com.lx.governance.aichat.service;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.lx.governance.aichat.dto.SessionContext;

import lombok.Builder;
import lombok.Value;

@Component
public class IntentMemoryFilterStrategy {

    public MemoryFilterDecision decide(SessionContext sessionContext) {
        String intent = normalize(sessionContext != null ? sessionContext.getUserIntent() : null);
        String topic = normalize(sessionContext != null ? sessionContext.getCurrentTopic() : null);
        String signal = intent + " " + topic;

        if (containsAny(signal, "药", "用药", "服药", "药物", "降压药", "胰岛素")) {
            return MemoryFilterDecision.builder()
                    .memoryTypes(setOf("MEDICATION", "ALLERGY", "SYMPTOM"))
                    .minConfidence(0.8)
                    .verifiedOnly(true)
                    .recentDays(180)
                    .strategyName("medication")
                    .build();
        }

        if (containsAny(signal, "饮食", "晚饭", "早餐", "午饭", "加餐", "吃", "食谱", "控糖饮食")) {
            return MemoryFilterDecision.builder()
                    .memoryTypes(setOf("DIET", "ALLERGY", "PREFERENCE"))
                    .minConfidence(0.7)
                    .verifiedOnly(false)
                    .recentDays(90)
                    .strategyName("diet")
                    .build();
        }

        if (containsAny(signal, "运动", "锻炼", "跑步", "健身", "训练")) {
            return MemoryFilterDecision.builder()
                    .memoryTypes(setOf("PREFERENCE", "SYMPTOM", "OTHER"))
                    .minConfidence(0.7)
                    .verifiedOnly(false)
                    .recentDays(120)
                    .strategyName("exercise")
                    .build();
        }

        if (containsAny(signal, "症状", "疼", "不舒服", "血糖", "血压", "头晕", "失眠", "腹泻", "过敏")) {
            return MemoryFilterDecision.builder()
                    .memoryTypes(setOf("SYMPTOM", "ALLERGY", "MEDICATION"))
                    .minConfidence(0.75)
                    .verifiedOnly(false)
                    .recentDays(45)
                    .strategyName("symptom")
                    .build();
        }

        return MemoryFilterDecision.builder()
                .memoryTypes(setOf("SYMPTOM", "DIET", "PREFERENCE", "OTHER"))
                .minConfidence(0.7)
                .verifiedOnly(false)
                .recentDays(90)
                .strategyName("default")
                .build();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> setOf(String... values) {
        Set<String> set = new LinkedHashSet<>();
        for (String value : values) {
            set.add(value);
        }
        return set;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    @Value
    @Builder
    public static class MemoryFilterDecision {
        Set<String> memoryTypes;
        Double minConfidence;
        Boolean verifiedOnly;
        Integer recentDays;
        String strategyName;
    }
}
