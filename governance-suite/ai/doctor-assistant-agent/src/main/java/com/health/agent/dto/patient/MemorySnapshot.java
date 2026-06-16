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
    /**
     * ai-chat-service 下传的短期记忆快照，通常来自最近对话摘要。
     */
    @Builder.Default
    private List<Object> shortTermMemories = new ArrayList<>();

    /**
     * ai-chat-service 下传的长期记忆快照，通常来自向量检索结果。
     */
    @Builder.Default
    private List<Object> longTermMemories = new ArrayList<>();
}
