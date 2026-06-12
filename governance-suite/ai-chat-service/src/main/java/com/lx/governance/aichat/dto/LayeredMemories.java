package com.lx.governance.aichat.dto;

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
public class LayeredMemories {

    @Builder.Default
    private List<String> shortTermMemories = new ArrayList<>();

    @Builder.Default
    private List<MemoryItemDTO> longTermMemories = new ArrayList<>();
}
