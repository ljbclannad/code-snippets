package com.health.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.health.agent.client.KnowledgeBaseClient;
import com.health.agent.dto.kb.KbSearchResult;
import com.health.agent.mapper.HealthKnowledgeMapper;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.HealthKnowledge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class HealthKnowledgeServiceTest {

    @Test
    void shouldPreferKnowledgeBaseResultsWhenAvailable() throws Exception {
        HealthKnowledgeMapper mapper = mock(HealthKnowledgeMapper.class);
        KnowledgeBaseClient client = mock(KnowledgeBaseClient.class);
        when(client.isEnabled()).thenReturn(true);

        KbSearchResult result = new KbSearchResult();
        result.setTitle("高血压运动建议");
        result.setContent("建议每周进行中等强度有氧运动。");
        result.setTags("运动,高血压");
        result.setSource("kb");
        result.setTrustLevel("HIGH");
        result.setHitReason("hybrid");
        result.setScore(0.91);
        result.setApplicableScope("doctor_assistant");
        when(client.searchMedicalKnowledge("高血压运动", 3, null, null, null, null))
                .thenReturn(List.of(result));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        HealthKnowledgeService service = new HealthKnowledgeService(mapper, client, new AgentMetrics(meterRegistry));

        List<HealthKnowledge> knowledgeList = service.getByTopic("高血压运动", 3);

        assertEquals(1, knowledgeList.size());
        assertEquals("高血压运动建议", knowledgeList.get(0).getTitle());
        assertEquals(HealthKnowledge.CategoryEnum.EXERCISE.name(), knowledgeList.get(0).getCategory());
        assertEquals("HIGH", knowledgeList.get(0).getTrustLevel());
        assertEquals("hybrid", knowledgeList.get(0).getHitReason());
        assertEquals(0.91, knowledgeList.get(0).getScore());
        assertEquals(1.0, meterRegistry.get("agent.kb.retrieval.total")
                .tag("source", "knowledge_base")
                .tag("outcome", "hit")
                .counter()
                .count());
    }

    @Test
    void shouldFallbackToLocalKnowledgeWhenKnowledgeBaseFails() throws Exception {
        HealthKnowledgeMapper mapper = mock(HealthKnowledgeMapper.class);
        KnowledgeBaseClient client = mock(KnowledgeBaseClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.searchMedicalKnowledge("减重饮食", 3, null, null, null, null))
                .thenThrow(new IOException("kb unavailable"));

        HealthKnowledge local = HealthKnowledge.builder()
                .title("本地减重饮食建议")
                .content("少糖少油，规律进餐。")
                .build();
        when(mapper.searchByKeyword("减重饮食", 3)).thenReturn(List.of(local));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        HealthKnowledgeService service = new HealthKnowledgeService(mapper, client, new AgentMetrics(meterRegistry));

        List<HealthKnowledge> knowledgeList = service.searchByKeyword("减重饮食", 3);

        assertFalse(knowledgeList.isEmpty());
        assertEquals("本地减重饮食建议", knowledgeList.get(0).getTitle());
        verify(mapper).searchByKeyword("减重饮食", 3);
        assertEquals(1.0, meterRegistry.get("agent.kb.retrieval.total")
                .tag("source", "knowledge_base")
                .tag("outcome", "error")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("agent.kb.retrieval.total")
                .tag("source", "local_db")
                .tag("outcome", "hit")
                .counter()
                .count());
    }
}
