package com.lx.governance.vectormemory.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class SemanticMemoryConflictDetector {

    private static final List<String> NEGATIVE_MARKERS = List.of("不", "无", "未", "否认", "避免", "忌口");
    private static final List<String> HISTORICAL_MARKERS = List.of("曾经", "之前", "以前", "过去");
    private static final List<String> CONDITIONAL_MARKERS = List.of("如果", "偶尔", "有时", "可能", "大概");
    private static final Pattern ALLERGY_PATTERN = Pattern.compile("(?:对)?(.{1,12}?)(?:过敏|敏感)");
    private static final Pattern MEDICATION_PATTERN = Pattern.compile("(?:服用|吃|使用)(.{1,12}?)(?:药|胶囊|片)?");

    private static final Map<String, String> CANONICAL_REPLACEMENTS = new LinkedHashMap<>();

    static {
        CANONICAL_REPLACEMENTS.put("不能吃", "不吃");
        CANONICAL_REPLACEMENTS.put("不爱吃", "不吃");
        CANONICAL_REPLACEMENTS.put("不喜欢吃", "不吃");
        CANONICAL_REPLACEMENTS.put("忌口", "不吃");
        CANONICAL_REPLACEMENTS.put("辛辣", "辣");
        CANONICAL_REPLACEMENTS.put("重辣", "辣");
        CANONICAL_REPLACEMENTS.put("辣的", "辣");
        CANONICAL_REPLACEMENTS.put("清淡饮食", "清淡");
        CANONICAL_REPLACEMENTS.put("清淡的", "清淡");
        CANONICAL_REPLACEMENTS.put("没有", "无");
        CANONICAL_REPLACEMENTS.put("未见", "无");
        CANONICAL_REPLACEMENTS.put("未出现", "无");
        CANONICAL_REPLACEMENTS.put("正在服用", "服用");
        CANONICAL_REPLACEMENTS.put("在吃", "服用");
        CANONICAL_REPLACEMENTS.put("爱吃", "喜欢");
        CANONICAL_REPLACEMENTS.put("偏好", "喜欢");
    }

    public SemanticMemoryFingerprint fingerprint(String memoryType, String content) {
        String raw = content == null ? "" : content.trim();
        String canonical = canonicalize(raw);
        String semanticType = inferSemanticType(memoryType, canonical);
        String semanticSubject = extractSubject(semanticType, canonical);
        String normalizedValue = extractValue(semanticType, canonical, semanticSubject);

        return SemanticMemoryFingerprint.builder()
                .normalizedKey(buildKey(memoryType, semanticType, semanticSubject, canonical))
                .normalizedValue(normalizedValue)
                .polarity(detectPolarity(canonical))
                .semanticType(semanticType)
                .semanticSubject(semanticSubject)
                .historical(containsAny(canonical, HISTORICAL_MARKERS))
                .conditional(containsAny(canonical, CONDITIONAL_MARKERS))
                .build();
    }

    public SemanticConflictAnalysis analyze(SemanticMemoryFingerprint left, SemanticMemoryFingerprint right) {
        if (left == null || right == null) {
            return SemanticConflictAnalysis.noConflict();
        }
        if (!safeEquals(left.getNormalizedKey(), right.getNormalizedKey())) {
            return SemanticConflictAnalysis.noConflict();
        }

        if (safeEquals(left.getNormalizedValue(), right.getNormalizedValue())
                && safeEquals(left.getPolarity(), right.getPolarity())) {
            return SemanticConflictAnalysis.builder()
                    .duplicate(true)
                    .conflict(false)
                    .conflictType("EXACT_DUPLICATE")
                    .conflictReason("semantic_exact_duplicate")
                    .conflictScore(1.0)
                    .build();
        }

        if (!safeEquals(left.getPolarity(), right.getPolarity())) {
            return SemanticConflictAnalysis.builder()
                    .duplicate(false)
                    .conflict(true)
                    .conflictType("POLARITY_OPPOSITION")
                    .conflictReason("semantic_polarity_opposition")
                    .conflictScore(0.98)
                    .build();
        }

        if (left.isHistorical() != right.isHistorical() || left.isConditional() != right.isConditional()) {
            return SemanticConflictAnalysis.builder()
                    .duplicate(false)
                    .conflict(true)
                    .conflictType("TEMPORAL_VARIANT")
                    .conflictReason("semantic_temporal_variant")
                    .conflictScore(0.78)
                    .build();
        }

        if (!safeEquals(left.getNormalizedValue(), right.getNormalizedValue())) {
            return SemanticConflictAnalysis.builder()
                    .duplicate(false)
                    .conflict(true)
                    .conflictType("VALUE_MISMATCH")
                    .conflictReason("semantic_value_mismatch")
                    .conflictScore(0.92)
                    .build();
        }

        return SemanticConflictAnalysis.noConflict();
    }

    private String buildKey(String memoryType, String semanticType, String semanticSubject, String canonical) {
        String subjectPart = !semanticSubject.isBlank() ? semanticSubject : fallbackKey(canonical);
        return (safe(memoryType) + "|" + safe(semanticType) + "|" + subjectPart).trim();
    }

    private String extractValue(String semanticType, String canonical, String semanticSubject) {
        return switch (semanticType) {
            case "ALLERGY" -> "过敏";
            case "DIET_TASTE" -> extractTaste(canonical);
            case "MEDICATION" -> normalizeMedicationState(canonical);
            case "SYMPTOM" -> semanticSubject;
            default -> canonical;
        };
    }

    private String inferSemanticType(String memoryType, String canonical) {
        if (canonical.contains("过敏")) {
            return "ALLERGY";
        }
        if (canonical.contains("服用") || canonical.contains("胰岛素") || canonical.contains("降压药")) {
            return "MEDICATION";
        }
        if (canonical.contains("清淡") || canonical.contains("辣") || canonical.contains("咸") || canonical.contains("甜")) {
            return "DIET_TASTE";
        }
        if ("SYMPTOM".equalsIgnoreCase(memoryType) || canonical.contains("头晕")
                || canonical.contains("腹泻") || canonical.contains("失眠") || canonical.contains("疼")) {
            return "SYMPTOM";
        }
        return "GENERAL";
    }

    private String extractSubject(String semanticType, String canonical) {
        return switch (semanticType) {
            case "ALLERGY" -> matchOrDefault(ALLERGY_PATTERN, canonical, "unknown_allergy");
            case "MEDICATION" -> matchOrDefault(MEDICATION_PATTERN, canonical, "unknown_medication");
            case "DIET_TASTE" -> "diet_taste";
            case "SYMPTOM" -> extractSymptom(canonical);
            default -> fallbackKey(canonical);
        };
    }

    private String extractSymptom(String canonical) {
        for (String symptom : List.of("头晕", "腹泻", "失眠", "胃痛", "头痛", "咳嗽", "发烧", "血糖高", "血压高")) {
            if (canonical.contains(symptom)) {
                return symptom;
            }
        }
        return fallbackKey(canonical);
    }

    private String extractTaste(String canonical) {
        for (String taste : List.of("清淡", "辣", "咸", "甜", "油腻")) {
            if (canonical.contains(taste)) {
                return taste;
            }
        }
        return canonical;
    }

    private String normalizeMedicationState(String canonical) {
        if (containsAny(canonical, NEGATIVE_MARKERS)) {
            return "未服用";
        }
        return "服用";
    }

    private String detectPolarity(String canonical) {
        if (containsAny(canonical, NEGATIVE_MARKERS)) {
            return "NEGATIVE";
        }
        if (containsAny(canonical, CONDITIONAL_MARKERS)) {
            return "CONDITIONAL";
        }
        return "POSITIVE";
    }

    private boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String matchOrDefault(Pattern pattern, String canonical, String defaultValue) {
        Matcher matcher = pattern.matcher(canonical);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return defaultValue;
    }

    private String fallbackKey(String canonical) {
        return canonical.length() <= 12 ? canonical : canonical.substring(0, 12);
    }

    private String canonicalize(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[，。；、,.!?！？\\s]+", "");
        for (Map.Entry<String, String> entry : CANONICAL_REPLACEMENTS.entrySet()) {
            normalized = normalized.replace(entry.getKey(), entry.getValue());
        }
        normalized = normalized.replace("用户", "");
        return normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean safeEquals(String left, String right) {
        return safe(left).equals(safe(right));
    }
}
