package com.lx.governance.profile.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class SemanticProfileConflictDetector {

    private static final Map<String, String> CANONICAL_REPLACEMENTS = new LinkedHashMap<>();

    static {
        CANONICAL_REPLACEMENTS.put("男士", "男");
        CANONICAL_REPLACEMENTS.put("女士", "女");
        CANONICAL_REPLACEMENTS.put("女性", "女");
        CANONICAL_REPLACEMENTS.put("男性", "男");
        CANONICAL_REPLACEMENTS.put("不吃辣", "不吃辣");
        CANONICAL_REPLACEMENTS.put("不能吃辣", "不吃辣");
        CANONICAL_REPLACEMENTS.put("忌辣", "不吃辣");
        CANONICAL_REPLACEMENTS.put("喜欢清淡饮食", "清淡");
        CANONICAL_REPLACEMENTS.put("偏好清淡饮食", "清淡");
        CANONICAL_REPLACEMENTS.put("清淡的", "清淡");
        CANONICAL_REPLACEMENTS.put("高频运动", "经常运动");
        CANONICAL_REPLACEMENTS.put("每周运动5次", "经常运动");
    }

    public SemanticProfileFingerprint fingerprint(String fieldPath, String rawValue) {
        String canonical = canonicalize(rawValue);
        String semanticType = inferSemanticType(fieldPath);
        return SemanticProfileFingerprint.builder()
                .fieldPath(fieldPath)
                .semanticKey(fieldPath == null ? "" : fieldPath)
                .normalizedValue(normalizeByField(fieldPath, canonical))
                .polarity(detectPolarity(canonical))
                .semanticType(semanticType)
                .build();
    }

    public SemanticProfileConflictAnalysis analyze(
            SemanticProfileFingerprint candidate,
            SemanticProfileFingerprint target) {
        if (candidate == null || target == null) {
            return SemanticProfileConflictAnalysis.noConflict();
        }
        if (!safe(candidate.getSemanticKey()).equals(safe(target.getSemanticKey()))) {
            return SemanticProfileConflictAnalysis.noConflict();
        }

        if (safe(candidate.getNormalizedValue()).equals(safe(target.getNormalizedValue()))
                && safe(candidate.getPolarity()).equals(safe(target.getPolarity()))) {
            return SemanticProfileConflictAnalysis.builder()
                    .duplicate(true)
                    .conflict(false)
                    .conflictType("EXACT_DUPLICATE")
                    .conflictReason("semantic_exact_duplicate")
                    .conflictScore(1.0)
                    .build();
        }

        if (!safe(candidate.getPolarity()).equals(safe(target.getPolarity()))) {
            return SemanticProfileConflictAnalysis.builder()
                    .duplicate(false)
                    .conflict(true)
                    .conflictType("POLARITY_OPPOSITION")
                    .conflictReason("semantic_polarity_opposition")
                    .conflictScore(0.97)
                    .build();
        }

        return SemanticProfileConflictAnalysis.builder()
                .duplicate(false)
                .conflict(true)
                .conflictType("VALUE_MISMATCH")
                .conflictReason("semantic_value_mismatch")
                .conflictScore(0.9)
                .build();
    }

    private String inferSemanticType(String fieldPath) {
        if (fieldPath == null) {
            return "GENERAL";
        }
        if (fieldPath.contains("gender")) {
            return "GENDER";
        }
        if (fieldPath.contains("dietPreference")) {
            return "DIET";
        }
        if (fieldPath.contains("exerciseFrequency")) {
            return "EXERCISE";
        }
        if (fieldPath.contains("bloodSugar") || fieldPath.contains("bmi") || fieldPath.contains("age")) {
            return "NUMERIC";
        }
        return "GENERAL";
    }

    private String normalizeByField(String fieldPath, String canonical) {
        if (fieldPath == null) {
            return canonical;
        }
        if (fieldPath.endsWith("gender")) {
            if (canonical.contains("男")) {
                return "男";
            }
            if (canonical.contains("女")) {
                return "女";
            }
        }
        if (fieldPath.endsWith("dietPreference")) {
            if (canonical.contains("清淡")) {
                return "清淡";
            }
            if (canonical.contains("辣")) {
                return "辣";
            }
        }
        if (fieldPath.endsWith("exerciseFrequency")) {
            if (canonical.contains("经常运动")) {
                return "经常运动";
            }
            if (canonical.contains("很少运动") || canonical.contains("不运动")) {
                return "很少运动";
            }
        }
        return canonical;
    }

    private String detectPolarity(String canonical) {
        if (canonical.contains("不") || canonical.contains("无") || canonical.contains("很少")) {
            return "NEGATIVE";
        }
        return "POSITIVE";
    }

    private String canonicalize(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[，。；、,.!?！？\\s]+", "");
        for (Map.Entry<String, String> entry : CANONICAL_REPLACEMENTS.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue());
        }
        return value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
