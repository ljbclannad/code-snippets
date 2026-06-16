package com.lx.governance.aichat.dto;

import lombok.Data;

/**
 * 向量记忆服务返回的单条长期记忆摘要。
 *
 * <p>ai-chat-service 不直接感知底层存储结构，而是消费这份轻量 DTO 来完成
 * 提示词拼装、记忆展示和 Agent 快照下传。</p>
 */
@Data
public class MemoryItemDTO {

    /**
     * 记忆记录主键。
     */
    private Long id;

    /**
     * 记忆正文内容。
     */
    private String content;

    /**
     * 向量召回相似度。
     */
    private Double similarity;

    /**
     * 记忆本身的业务置信度。
     */
    private Double confidenceScore;

    /**
     * 是否已被确认或治理通过。
     */
    private Boolean verified;

    /**
     * 记忆来源，例如聊天提取、人工录入或系统生成。
     */
    private String source;
}
