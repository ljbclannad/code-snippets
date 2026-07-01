package com.lx.governance.vectormemory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 向量记忆服务对外返回的单条记忆结果。
 *
 * <p>它屏蔽底层实体细节，只暴露对上游有意义的内容、相似度和治理状态。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryItemDTO {

    /**
     * Memory record ID
     */
    private Long id;

    /**
     * Memory content text
     */
    private String content;

    /**
     * Vector similarity score (0.0 to 1.0)
     */
    private Double similarity;

    /**
     * Confidence score for this memory
     */
    private Double confidenceScore;

    /**
     * Whether this memory has been verified
     */
    private Boolean verified;

    /**
     * Source of this memory (e.g., "chat", "extraction", "manual")
     */
    private String source;

    /**
     * Memory representation (EVIDENCE/SUMMARY)
     */
    private String memoryRepresentation;

    /**
     * Summary group key for evidence backtracking
     */
    private String summaryGroupKey;
}
