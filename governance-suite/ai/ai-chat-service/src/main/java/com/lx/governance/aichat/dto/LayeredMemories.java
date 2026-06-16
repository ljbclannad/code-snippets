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

    /**
     * ai-chat-service 在当前轮对话中聚合出的分层记忆快照。
     *
     * <p>短期记忆来自 SessionContext 的最近对话摘要，长期记忆来自
     * vector-memory-service 的语义检索结果。它既用于普通对话提示词拼装，
     * 也会在升级到 doctor-assistant-agent 时整体下传。</p>
     */
    @Builder.Default
    private List<String> shortTermMemories = new ArrayList<>();

    /**
     * 向量检索返回的长期记忆条目，保留相似度、置信度和来源信息。
     */
    @Builder.Default
    private List<MemoryItemDTO> longTermMemories = new ArrayList<>();
}
