package com.health.agent.dto.patient;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemorySnapshot {
    @Builder.Default
    private List<Object> shortTermMemories = new ArrayList<>();

    @Builder.Default
    private List<Object> longTermMemories = new ArrayList<>();
}
