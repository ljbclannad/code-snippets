package com.health.agent.model;

import com.alibaba.fastjson2.JSON;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {

    private String customerId;
    private String doctorId;
    private String goal;

    @Builder.Default
    private Map<String, ContextEntry> slots = new LinkedHashMap<>();

    @Builder.Default
    private List<EvidenceRecord> evidenceRecords = new ArrayList<>();

    public static AgentContext initialize(String customerId, String doctorId, String goal) {
        return AgentContext.builder()
            .customerId(customerId)
            .doctorId(doctorId)
            .goal(goal)
            .slots(new LinkedHashMap<>())
            .evidenceRecords(new ArrayList<>())
            .build();
    }

    public static AgentContext empty() {
        return initialize(null, null, null);
    }

    public void putSlot(String key, Object value, String source) {
        if (key == null || key.isBlank()) {
            return;
        }
        slots.put(key, ContextEntry.builder()
            .key(key)
            .value(value)
            .source(source)
            .updatedAt(LocalDateTime.now())
            .build());
    }

    public void addEvidence(String toolName, String source, String summary, Object payload) {
        evidenceRecords.add(EvidenceRecord.builder()
            .toolName(toolName)
            .source(source)
            .summary(summary)
            .payload(payload)
            .recordedAt(LocalDateTime.now())
            .build());
    }

    public String toPromptSection() {
        if (slots == null || slots.isEmpty()) {
            return "当前无已缓存状态。";
        }

        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, ContextEntry> entry : slots.entrySet()) {
            String serialized = JSON.toJSONString(entry.getValue().getValue());
            if (serialized.length() > 300) {
                serialized = serialized.substring(0, 300) + "...";
            }
            builder.append("- ")
                .append(entry.getKey())
                .append(": ")
                .append(serialized)
                .append('\n');
        }
        return builder.toString().trim();
    }
}
