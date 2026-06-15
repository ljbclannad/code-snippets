package com.health.agent.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.health.agent.metrics.VectorMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class EmbeddingGeneratorTest {

    private EmbeddingGenerator embeddingGenerator;

    @BeforeEach
    void setUp() {
        embeddingGenerator = new EmbeddingGenerator();
        ReflectionTestUtils.setField(
            embeddingGenerator,
            "vectorMetrics",
            new VectorMetrics(new SimpleMeterRegistry())
        );
        ReflectionTestUtils.setField(embeddingGenerator, "dimension", 8);
    }

    @Test
    void shouldGenerateMockEmbeddingWhenMockModeEnabled() {
        ReflectionTestUtils.setField(embeddingGenerator, "useMock", true);

        assertEquals(8, embeddingGenerator.generateEmbedding("patient case").size());
    }

    @Test
    void shouldFailFastWhenRealEmbeddingProviderIsNotConfigured() {
        ReflectionTestUtils.setField(embeddingGenerator, "useMock", false);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> embeddingGenerator.generateEmbedding("patient case")
        );

        assertEquals(
            "REAL_EMBEDDING_NOT_IMPLEMENTED: embedding.use-mock=false, 但当前项目尚未接入真实 Embedding Provider",
            exception.getMessage()
        );
    }
}
