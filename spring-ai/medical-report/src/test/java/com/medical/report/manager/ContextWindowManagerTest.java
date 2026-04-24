package com.medical.report.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextWindowManagerTest {

    private ContextWindowManager contextWindowManager;

    @BeforeEach
    void setUp() {
        contextWindowManager = new ContextWindowManager();
    }

    @Test
    void testEstimateTokens() {
        String text = "This is a test string with approximately 10 words in it.";
        int tokens = contextWindowManager.estimateTokens(text);
        assertTrue(tokens > 0, "Token count should be positive");
        assertTrue(tokens < 20, "Token count should be reasonable");
    }

    @Test
    void testTruncateToTokenLimit() {
        String longText = "A".repeat(1000);
        String truncated = contextWindowManager.truncateToTokenLimit(longText, 50);
        int truncatedTokens = contextWindowManager.estimateTokens(truncated);
        assertTrue(truncatedTokens <= 60, "Truncated text should be within limit");
    }

    @Test
    void testOptimizeContent() {
        String content = "Short content";
        String optimized = contextWindowManager.optimizeContent(content, 1000);
        assertEquals(content, optimized, "Short content should not be modified");
    }

    @Test
    void testIsWithinLimit() {
        String shortText = "Short text";
        assertTrue(contextWindowManager.isWithinLimit(shortText));

        String longText = "A".repeat(500000);
        assertFalse(contextWindowManager.isWithinLimit(longText));
    }

    @Test
    void testCompressWhitespace() {
        String text = "This   has    multiple     spaces";
        String compressed = contextWindowManager.compressWhitespace(text);
        assertEquals("This has multiple spaces", compressed);
    }

    @Test
    void testSummarizeIfNeeded() {
        String content = "Section 1\n\nSection 2\n\nSection 3";
        String summarized = contextWindowManager.summarizeIfNeeded(content, 5);
        assertNotNull(summarized);
        assertTrue(summarized.length() <= content.length());
    }
}
