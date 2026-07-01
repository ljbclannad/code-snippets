package com.health.agent.learning.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.health.agent.learning.entity.AgentRunOutcomeEntity;
import com.health.agent.learning.enums.AgentOutcomeLabel;
import com.health.agent.learning.mapper.AgentRunOutcomeMapper;
import com.health.agent.learning.model.AgentRunOutcome;

class DefaultAgentOutcomeRecorderTest {

    @Test
    void shouldInsertWhenRunDoesNotExist() {
        AgentRunOutcomeMapper mapper = mock(AgentRunOutcomeMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        DefaultAgentOutcomeRecorder recorder = new DefaultAgentOutcomeRecorder(mapper);

        recorder.createOrUpdate(AgentRunOutcome.builder()
                .runId("run-1")
                .sessionId("run-1")
                .scene("doctor")
                .finalStatus("SUCCESS")
                .outcomeLabel(AgentOutcomeLabel.SUCCESS)
                .fallbackUsed(Boolean.FALSE)
                .build());

        ArgumentCaptor<AgentRunOutcomeEntity> captor = ArgumentCaptor.forClass(AgentRunOutcomeEntity.class);
        verify(mapper).insert(captor.capture());
        verify(mapper, never()).updateById(any(AgentRunOutcomeEntity.class));
        assertEquals("run-1", captor.getValue().getRunId());
        assertEquals("SUCCESS", captor.getValue().getOutcomeLabel());
    }

    @Test
    void shouldUpdateExistingRunByRunId() {
        AgentRunOutcomeMapper mapper = mock(AgentRunOutcomeMapper.class);
        AgentRunOutcomeEntity existing = new AgentRunOutcomeEntity();
        existing.setId(10L);
        existing.setRunId("run-2");
        existing.setFinalStatus("ERROR");
        existing.setOutcomeLabel("FAILED_RUNTIME");
        existing.setFallbackUsed(Boolean.FALSE);
        when(mapper.selectOne(any())).thenReturn(existing);
        DefaultAgentOutcomeRecorder recorder = new DefaultAgentOutcomeRecorder(mapper);

        recorder.createOrUpdate(AgentRunOutcome.builder()
                .runId("run-2")
                .outcomeLabel(AgentOutcomeLabel.SUCCESS_WITH_FALLBACK)
                .fallbackUsed(Boolean.TRUE)
                .build());

        ArgumentCaptor<AgentRunOutcomeEntity> captor = ArgumentCaptor.forClass(AgentRunOutcomeEntity.class);
        verify(mapper).updateById(captor.capture());
        verify(mapper, never()).insert(any(AgentRunOutcomeEntity.class));
        assertEquals(10L, captor.getValue().getId());
        assertEquals("run-2", captor.getValue().getRunId());
        assertEquals("SUCCESS_WITH_FALLBACK", captor.getValue().getOutcomeLabel());
        assertEquals(Boolean.TRUE, captor.getValue().getFallbackUsed());
        assertEquals("ERROR", captor.getValue().getFinalStatus());
    }
}
