package com.health.agent.vector;

import com.health.agent.metrics.VectorMetrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorSearchServiceTest {

    private VectorSearchService vectorSearchService;
    private MilvusClient milvusClient;
    private EmbeddingGenerator embeddingGenerator;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        vectorSearchService = new VectorSearchService();
        milvusClient = mock(MilvusClient.class);
        embeddingGenerator = mock(EmbeddingGenerator.class);
        meterRegistry = new SimpleMeterRegistry();

        ReflectionTestUtils.setField(vectorSearchService, "milvusClient", milvusClient);
        ReflectionTestUtils.setField(vectorSearchService, "embeddingGenerator", embeddingGenerator);
        ReflectionTestUtils.setField(vectorSearchService, "vectorMetrics", new VectorMetrics(meterRegistry));
        ReflectionTestUtils.setField(vectorSearchService, "defaultTopK", 5);
    }

    @Test
    void shouldRecordFailureDurationWhenSearchFails() {
        when(embeddingGenerator.generateEmbedding("fever")).thenThrow(new IllegalStateException("embedding down"));

        List<CaseVector> results = vectorSearchService.searchSimilarCases("fever", 3);

        assertTrue(results.isEmpty());
        Timer failureTimer = meterRegistry.find("vector.search.duration")
            .tag("outcome", "failure")
            .timer();
        assertNotNull(failureTimer);
        assertEquals(1, failureTimer.count());
        assertEquals(
            1,
            meterRegistry.get("vector.search.failure")
                .tag("error_type", "IllegalStateException")
                .counter()
                .count()
        );
    }
}
