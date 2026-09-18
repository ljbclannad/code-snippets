package com.lx.governance.aichat.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lx.governance.aichat.dto.ChatRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 白名单投影后的补充脱敏；不能代替自由文本中的完整个人信息识别。 */
final class CaptureSanitizer {
    private final ObjectMapper mapper;
    private final String key;
    private final int maxText;
    private final List<String> identifiers = new ArrayList<>();
    private boolean truncated;

    CaptureSanitizer(ObjectMapper mapper, EvaluationCaptureProperties properties, ChatRequest request) {
        this.mapper = mapper;
        key = properties.getHashKey();
        maxText = properties.getMaxTextLength();
        for (String value : new String[]{request.getCustomerId(), request.getUserId(), request.getGroupId(),
                request.getTenantId(), request.getConversationId(), request.getRequestId()}) {
            if (value != null && !value.isBlank()) { identifiers.add(value); }
        }
        identifiers.sort(Comparator.comparingInt(String::length).reversed());
    }

    String hash(String value) {
        if (value == null || value.isBlank()) { return null; }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("无法生成采集关联摘要", e); }
    }

    String text(String value) {
        if (value == null) { return null; }
        String result = value;
        for (String id : identifiers) {
            result = id.length() >= 4 ? result.replace(id, "[标识已脱敏]")
                    : result.replaceAll("(?<![A-Za-z0-9])" + Pattern.quote(id) + "(?![A-Za-z0-9])", "[标识已脱敏]");
        }
        result = result.replaceAll("(?<![0-9])[0-9]{17}[0-9Xx](?![0-9])", "[证件已脱敏]")
                .replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])", "[电话已脱敏]")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[邮箱已脱敏]");
        if (result.length() > maxText) { truncated = true; return result.substring(0, maxText); }
        return result;
    }

    JsonNode tree(JsonNode source) { return tree(source, 0); }

    private JsonNode tree(JsonNode source, int depth) {
        if (source == null) { return mapper.nullNode(); }
        if (depth > 12) { truncated = true; return mapper.nullNode(); }
        if (source.isTextual()) { return mapper.getNodeFactory().textNode(text(source.asText())); }
        if (source.isArray()) {
            var target = mapper.createArrayNode();
            for (int i = 0; i < source.size() && i < 100; i++) { target.add(tree(source.get(i), depth + 1)); }
            if (source.size() > 100) { truncated = true; }
            return target;
        }
        if (source.isObject()) {
            ObjectNode target = mapper.createObjectNode();
            source.fields().forEachRemaining(entry -> {
                String field = entry.getKey();
                String normalized = field.toLowerCase(Locale.ROOT);
                if (normalized.matches(".*(password|secret|authorization|apikey|access_token|hashkey).*")) { return; }
                if (normalized.endsWith("id") && entry.getValue().isValueNode()) {
                    target.put(field + "Hash", hash(entry.getValue().asText()));
                } else { target.set(field, tree(entry.getValue(), depth + 1)); }
            });
            return target;
        }
        return source.deepCopy();
    }

    boolean truncated() { return truncated; }
}
