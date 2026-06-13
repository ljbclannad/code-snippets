package com.lx.governance.vectormemory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Memory item data transfer object
 * Used for returning memory search results with similarity scores
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
}
