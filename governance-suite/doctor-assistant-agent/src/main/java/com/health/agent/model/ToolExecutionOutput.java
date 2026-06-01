package com.health.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionOutput {
    private Object data;
    private String summary;

    @Builder.Default
    private Map<String, Object> stateUpdates = new LinkedHashMap<>();

    @Builder.Default
    private List<ToolEvidence> evidence = List.of();
}
