package com.health.agent.tool;

import com.health.agent.exception.AgentException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于工具 schema 的输入校验器。
 */
@Component
public class ToolInputValidator {

    public Map<String, Object> validateAndNormalize(Tool tool, Map<String, Object> rawInput) {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> input = rawInput == null ? Map.of() : rawInput;

        if (schema == null || schema.isEmpty()) {
            return input;
        }

        rejectUnknownFields(schema, input);

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String field = entry.getKey();
            Map<String, Object> rules = castRules(field, entry.getValue());
            Object value = input.containsKey(field) ? input.get(field) : rules.get("default");

            if (isRequired(rules) && value == null) {
                throw invalid("缺少必填字段 `%s`".formatted(field));
            }
            if (value == null) {
                continue;
            }

            normalized.put(field, normalizeValue(field, value, rules));
        }
        return normalized;
    }

    private void rejectUnknownFields(Map<String, Object> schema, Map<String, Object> input) {
        List<String> unknownFields = input.keySet().stream()
            .filter(field -> !schema.containsKey(field))
            .toList();
        if (!unknownFields.isEmpty()) {
            throw invalid("包含未定义字段 %s".formatted(unknownFields));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castRules(String field, Object rules) {
        if (!(rules instanceof Map<?, ?> map)) {
            throw invalid("字段 `%s` 的 schema 定义非法".formatted(field));
        }
        return (Map<String, Object>) map;
    }

    private boolean isRequired(Map<String, Object> rules) {
        return Boolean.TRUE.equals(rules.get("required"));
    }

    private Object normalizeValue(String field, Object value, Map<String, Object> rules) {
        String type = String.valueOf(rules.getOrDefault("type", "string"));
        return switch (type) {
            case "string" -> normalizeString(field, value, rules);
            case "integer" -> normalizeInteger(field, value, rules);
            case "number" -> normalizeNumber(field, value, rules);
            case "array" -> normalizeArray(field, value, rules);
            default -> throw invalid("字段 `%s` 的类型 `%s` 不受支持".formatted(field, type));
        };
    }

    private String normalizeString(String field, Object value, Map<String, Object> rules) {
        if (!(value instanceof String stringValue)) {
            throw invalid("字段 `%s` 必须是字符串".formatted(field));
        }
        Number maxLength = asNumber(rules.get("maxLength"));
        if (maxLength != null && stringValue.length() > maxLength.intValue()) {
            throw invalid("字段 `%s` 长度超过限制 %d".formatted(field, maxLength.intValue()));
        }
        return stringValue;
    }

    private Integer normalizeInteger(String field, Object value, Map<String, Object> rules) {
        Integer integerValue;
        if (value instanceof Integer intValue) {
            integerValue = intValue;
        } else if (value instanceof Number numberValue) {
            integerValue = numberValue.intValue();
        } else {
            throw invalid("字段 `%s` 必须是整数".formatted(field));
        }
        applyNumberRange(field, integerValue.doubleValue(), rules);
        return integerValue;
    }

    private Double normalizeNumber(String field, Object value, Map<String, Object> rules) {
        if (!(value instanceof Number numberValue)) {
            throw invalid("字段 `%s` 必须是数字".formatted(field));
        }
        double normalized = numberValue.doubleValue();
        applyNumberRange(field, normalized, rules);
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private List<Object> normalizeArray(String field, Object value, Map<String, Object> rules) {
        if (!(value instanceof List<?> listValue)) {
            throw invalid("字段 `%s` 必须是数组".formatted(field));
        }
        Number minItems = asNumber(rules.get("minItems"));
        Number maxItems = asNumber(rules.get("maxItems"));
        if (minItems != null && listValue.size() < minItems.intValue()) {
            throw invalid("字段 `%s` 元素数量少于限制 %d".formatted(field, minItems.intValue()));
        }
        if (maxItems != null && listValue.size() > maxItems.intValue()) {
            throw invalid("字段 `%s` 元素数量超过限制 %d".formatted(field, maxItems.intValue()));
        }

        String itemType = rules.get("itemType") != null ? String.valueOf(rules.get("itemType")) : null;
        if (itemType == null) {
            return new ArrayList<>((List<Object>) listValue);
        }

        List<Object> normalized = new ArrayList<>(listValue.size());
        for (Object element : listValue) {
            normalized.add(normalizeValue(field + "[]", element, Map.of("type", itemType)));
        }
        return normalized;
    }

    private void applyNumberRange(String field, double value, Map<String, Object> rules) {
        Number min = asNumber(rules.get("min"));
        Number max = asNumber(rules.get("max"));
        if (min != null && value < min.doubleValue()) {
            throw invalid("字段 `%s` 不能小于 %s".formatted(field, min));
        }
        if (max != null && value > max.doubleValue()) {
            throw invalid("字段 `%s` 不能大于 %s".formatted(field, max));
        }
    }

    private Number asNumber(Object value) {
        return value instanceof Number number ? number : null;
    }

    private AgentException invalid(String message) {
        return new AgentException("INVALID_TOOL_INPUT: " + message);
    }
}
