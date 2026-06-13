package com.lx.governance.vectormemory.dto;

import lombok.Data;

import java.util.List;

/**
 * Result of memory extraction from conversation
 * Contains extracted memories with confidence scores
 */
@Data
public class MemoryExtractionResult {

    /**
     * List of extracted memories
     */
    private List<ExtractedMemory> memories;

    /**
     * Individual extracted memory item
     */
    @Data
    public static class ExtractedMemory {

        /**
         * Extracted memory content
         */
        private String content;

        /**
         * Confidence score (0.0 to 1.0)
         */
        private Double confidence;

        /**
         * Memory category (e.g., "preference", "fact", "context")
         */
        private String category;
    }
}
