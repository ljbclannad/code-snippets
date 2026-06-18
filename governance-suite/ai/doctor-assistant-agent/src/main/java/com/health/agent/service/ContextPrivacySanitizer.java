package com.health.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.health.agent.config.DoctorSafetyProperties;
import com.health.agent.dto.patient.MemorySnapshot;

/**
 * 统一处理面向模型/协作暴露面的敏感信息脱敏。
 */
@Service
public class ContextPrivacySanitizer {

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(1\\d{10})(?!\\d)");
    private static final Pattern IDCARD_PATTERN = Pattern.compile("(?<!\\w)(\\d{17}[\\dXx])(?!\\w)");

    private final DoctorSafetyProperties safetyProperties;

    public ContextPrivacySanitizer(DoctorSafetyProperties safetyProperties) {
        this.safetyProperties = safetyProperties != null ? safetyProperties : new DoctorSafetyProperties();
    }

    public Object sanitizeForExposure(String slotKey, Object value) {
        if (!isEnabled() || value == null) {
            return value;
        }
        return sanitizeNode(slotKey, value);
    }

    public String sanitizeSummary(String slotKey, String summary) {
        if (!isEnabled() || summary == null) {
            return summary;
        }
        return (String) sanitizeNode(slotKey, summary);
    }

    private boolean isEnabled() {
        return safetyProperties.getPrivacy() != null && safetyProperties.getPrivacy().isEnabled();
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeNode(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof MemorySnapshot snapshot) {
            return MemorySnapshot.builder()
                    .shortTermMemories(sanitizeMemoryList("shortTermMemories", snapshot.getShortTermMemories()))
                    .longTermMemories(sanitizeMemoryList("longTermMemories", snapshot.getLongTermMemories()))
                    .build();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((entryKey, entryValue) -> sanitized.put(
                    String.valueOf(entryKey),
                    sanitizeNode(String.valueOf(entryKey), entryValue)));
            return sanitized;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>(list.size());
            for (Object item : list) {
                sanitized.add(sanitizeNode(key, item));
            }
            return sanitized;
        }
        if (value instanceof String text) {
            return sanitizeText(key, text);
        }
        return value;
    }

    private List<Object> sanitizeMemoryList(String key, List<Object> values) {
        if (values == null) {
            return null;
        }
        List<Object> sanitized = new ArrayList<>(values.size());
        for (Object value : values) {
            sanitized.add(sanitizeNode(key, value));
        }
        return sanitized;
    }

    private String sanitizeText(String key, String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String sanitized = text;
        if (matchesConfiguredKey(key, safetyProperties.getPrivacy().getNameKeys())) {
            sanitized = maskName(sanitized);
        } else if (matchesConfiguredKey(key, safetyProperties.getPrivacy().getPhoneKeys())) {
            sanitized = maskPhone(sanitized);
        } else if (matchesConfiguredKey(key, safetyProperties.getPrivacy().getIdCardKeys())) {
            sanitized = maskIdCard(sanitized);
        } else if (matchesConfiguredKey(key, safetyProperties.getPrivacy().getAddressKeys())) {
            sanitized = maskAddress(sanitized);
        }
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll(match -> maskPhone(match.group(1)));
        sanitized = IDCARD_PATTERN.matcher(sanitized).replaceAll(match -> maskIdCard(match.group(1)));
        return sanitized;
    }

    private boolean matchesConfiguredKey(String key, List<String> configuredKeys) {
        if (key == null || configuredKeys == null) {
            return false;
        }
        return configuredKeys.stream().anyMatch(candidate -> candidate != null && candidate.equalsIgnoreCase(key));
    }

    private String maskName(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.length() <= 1) {
            return "*";
        }
        if (value.length() == 2) {
            return value.charAt(0) + "*";
        }
        return value.charAt(0) + "*" + value.charAt(value.length() - 1);
    }

    private String maskPhone(String value) {
        if (value == null || value.length() < 7) {
            return value;
        }
        if (value.length() == 11 && value.chars().allMatch(Character::isDigit)) {
            return value.substring(0, 3) + "****" + value.substring(7);
        }
        return value.substring(0, Math.min(3, value.length())) + "****";
    }

    private String maskIdCard(String value) {
        if (value == null || value.length() < 8) {
            return value;
        }
        return value.substring(0, 3) + "***********" + value.substring(value.length() - 4);
    }

    private String maskAddress(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        int keep = Math.min(6, value.length());
        return value.substring(0, keep) + "***";
    }
}
