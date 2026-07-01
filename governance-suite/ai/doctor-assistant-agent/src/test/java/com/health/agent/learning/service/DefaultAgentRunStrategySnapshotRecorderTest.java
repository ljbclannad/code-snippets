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

import com.health.agent.learning.entity.AgentRunStrategySnapshotEntity;
import com.health.agent.learning.mapper.AgentRunStrategySnapshotMapper;
import com.health.agent.learning.model.AgentRunStrategySnapshot;

class DefaultAgentRunStrategySnapshotRecorderTest {

    @Test
    void shouldInsertWhenSnapshotDoesNotExist() {
        AgentRunStrategySnapshotMapper mapper = mock(AgentRunStrategySnapshotMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        DefaultAgentRunStrategySnapshotRecorder recorder = new DefaultAgentRunStrategySnapshotRecorder(mapper);

        recorder.createOrUpdate(AgentRunStrategySnapshot.builder()
                .runId("run-1")
                .sessionId("run-1")
                .scene("doctor")
                .taskType("PATIENT_FOLLOW_UP_ANALYSIS")
                .selectedAgentIds(List.of("risk-triage"))
                .build());

        ArgumentCaptor<AgentRunStrategySnapshotEntity> captor =
                ArgumentCaptor.forClass(AgentRunStrategySnapshotEntity.class);
        verify(mapper).insert(captor.capture());
        verify(mapper, never()).updateById(any(AgentRunStrategySnapshotEntity.class));
        assertEquals("run-1", captor.getValue().getRunId());
        assertEquals("doctor", captor.getValue().getScene());
    }

    @Test
    void shouldUpdateExistingSnapshotByRunId() {
        AgentRunStrategySnapshotMapper mapper = mock(AgentRunStrategySnapshotMapper.class);
        AgentRunStrategySnapshotEntity existing = new AgentRunStrategySnapshotEntity();
        existing.setId(20L);
        existing.setRunId("run-2");
        existing.setScene("doctor");
        existing.setSelectionMode("SERIAL");
        when(mapper.selectOne(any())).thenReturn(existing);
        DefaultAgentRunStrategySnapshotRecorder recorder = new DefaultAgentRunStrategySnapshotRecorder(mapper);

        recorder.createOrUpdate(AgentRunStrategySnapshot.builder()
                .runId("run-2")
                .selectionMode("PARALLEL")
                .selectedAgentIds(List.of("risk-triage", "patient-data-collection"))
                .memoryProfile(Map.of("hasMemorySnapshot", true))
                .build());

        ArgumentCaptor<AgentRunStrategySnapshotEntity> captor =
                ArgumentCaptor.forClass(AgentRunStrategySnapshotEntity.class);
        verify(mapper).updateById(captor.capture());
        verify(mapper, never()).insert(any(AgentRunStrategySnapshotEntity.class));
        assertEquals(20L, captor.getValue().getId());
        assertEquals("run-2", captor.getValue().getRunId());
        assertEquals("PARALLEL", captor.getValue().getSelectionMode());
        assertEquals("doctor", captor.getValue().getScene());
    }
}
