package com.health.agent.learning.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.health.agent.learning.entity.AgentOutcomeAttributionEntity;
import com.health.agent.learning.enums.AgentPrimaryCause;
import com.health.agent.learning.mapper.AgentOutcomeAttributionMapper;
import com.health.agent.learning.model.AgentOutcomeAttribution;

class DefaultAgentOutcomeAttributionServiceTest {

    @Test
    void shouldInsertWhenRunDoesNotExist() {
        AgentOutcomeAttributionMapper mapper = mock(AgentOutcomeAttributionMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        DefaultAgentOutcomeAttributionService service = new DefaultAgentOutcomeAttributionService(mapper);

        service.createOrUpdate(AgentOutcomeAttribution.builder()
                .runId("run-attr-1")
                .sessionId("run-attr-1")
                .scene("doctor")
                .primaryCause(AgentPrimaryCause.PROMPT_WEAK)
                .secondaryCauses(List.of("memory_missing"))
                .evidenceSummary(Map.of("promptVersion", "v1"))
                .confidence(0.82D)
                .build());

        ArgumentCaptor<AgentOutcomeAttributionEntity> captor =
                ArgumentCaptor.forClass(AgentOutcomeAttributionEntity.class);
        verify(mapper).insert(captor.capture());
        verify(mapper, never()).updateById(any(AgentOutcomeAttributionEntity.class));
        assertEquals("run-attr-1", captor.getValue().getRunId());
        assertEquals("PROMPT_WEAK", captor.getValue().getPrimaryCause());
    }

    @Test
    void shouldUpdateExistingRunByRunId() {
        AgentOutcomeAttributionMapper mapper = mock(AgentOutcomeAttributionMapper.class);
        AgentOutcomeAttributionEntity existing = new AgentOutcomeAttributionEntity();
        existing.setId(20L);
        existing.setRunId("run-attr-2");
        existing.setPrimaryCause("UNKNOWN");
        when(mapper.selectOne(any())).thenReturn(existing);
        DefaultAgentOutcomeAttributionService service = new DefaultAgentOutcomeAttributionService(mapper);

        service.createOrUpdate(AgentOutcomeAttribution.builder()
                .runId("run-attr-2")
                .primaryCause(AgentPrimaryCause.TOOL_UNRELIABLE)
                .confidence(0.74D)
                .build());

        ArgumentCaptor<AgentOutcomeAttributionEntity> captor =
                ArgumentCaptor.forClass(AgentOutcomeAttributionEntity.class);
        verify(mapper).updateById(captor.capture());
        verify(mapper, never()).insert(any(AgentOutcomeAttributionEntity.class));
        assertEquals(20L, captor.getValue().getId());
        assertEquals("run-attr-2", captor.getValue().getRunId());
        assertEquals("TOOL_UNRELIABLE", captor.getValue().getPrimaryCause());
        assertEquals(0.74D, captor.getValue().getConfidence());
    }
}
