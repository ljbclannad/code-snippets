package com.health.agent.learning.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.health.agent.learning.entity.AgentOutcomeAttributionEntity;
import com.health.agent.learning.enums.AgentOutcomeLabel;
import com.health.agent.learning.enums.AgentPrimaryCause;
import com.health.agent.learning.mapper.AgentOutcomeAttributionMapper;
import com.health.agent.learning.model.AgentOutcomeAttribution;
import com.health.agent.learning.model.AgentRunOutcome;
import com.health.agent.learning.model.AgentRunStrategySnapshot;

class DefaultAgentOutcomeAttributionServiceTest {

    @Test
    void shouldInsertWhenRunDoesNotExist() {
        AgentOutcomeAttributionMapper mapper = mock(AgentOutcomeAttributionMapper.class);
        AgentOutcomeRecorder outcomeRecorder = mock(AgentOutcomeRecorder.class);
        AgentRunStrategySnapshotRecorder snapshotRecorder = mock(AgentRunStrategySnapshotRecorder.class);
        when(mapper.selectOne(any())).thenReturn(null);
        DefaultAgentOutcomeAttributionService service =
                new DefaultAgentOutcomeAttributionService(mapper, outcomeRecorder, snapshotRecorder);

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
        AgentOutcomeRecorder outcomeRecorder = mock(AgentOutcomeRecorder.class);
        AgentRunStrategySnapshotRecorder snapshotRecorder = mock(AgentRunStrategySnapshotRecorder.class);
        AgentOutcomeAttributionEntity existing = new AgentOutcomeAttributionEntity();
        existing.setId(20L);
        existing.setRunId("run-attr-2");
        existing.setPrimaryCause("UNKNOWN");
        when(mapper.selectOne(any())).thenReturn(existing);
        DefaultAgentOutcomeAttributionService service =
                new DefaultAgentOutcomeAttributionService(mapper, outcomeRecorder, snapshotRecorder);

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

    @Test
    void shouldRecomputeTimeoutAttribution() {
        AgentOutcomeAttributionMapper mapper = mock(AgentOutcomeAttributionMapper.class);
        AgentOutcomeRecorder outcomeRecorder = mock(AgentOutcomeRecorder.class);
        AgentRunStrategySnapshotRecorder snapshotRecorder = mock(AgentRunStrategySnapshotRecorder.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(outcomeRecorder.findByRunId("run-timeout")).thenReturn(Optional.of(AgentRunOutcome.builder()
                .runId("run-timeout")
                .sessionId("run-timeout")
                .scene("doctor")
                .outcomeLabel(AgentOutcomeLabel.FAILED_TIMEOUT)
                .errorMessage("DeepSeek API 调用超时")
                .build()));
        DefaultAgentOutcomeAttributionService service =
                new DefaultAgentOutcomeAttributionService(mapper, outcomeRecorder, snapshotRecorder);

        AgentOutcomeAttribution attribution = service.recompute("run-timeout").orElseThrow();

        assertEquals(AgentPrimaryCause.RUNTIME_TIMEOUT, attribution.getPrimaryCause());
        assertTrue(attribution.getConfidence() >= 0.9D);
    }

    @Test
    void shouldRecomputeToolUnreliableAttribution() {
        AgentOutcomeAttributionMapper mapper = mock(AgentOutcomeAttributionMapper.class);
        AgentOutcomeRecorder outcomeRecorder = mock(AgentOutcomeRecorder.class);
        AgentRunStrategySnapshotRecorder snapshotRecorder = mock(AgentRunStrategySnapshotRecorder.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(outcomeRecorder.findByRunId("run-tool")).thenReturn(Optional.of(AgentRunOutcome.builder()
                .runId("run-tool")
                .sessionId("run-tool")
                .scene("doctor")
                .outcomeLabel(AgentOutcomeLabel.FAILED_TOOL_ERROR)
                .toolErrorCount(2)
                .build()));
        DefaultAgentOutcomeAttributionService service =
                new DefaultAgentOutcomeAttributionService(mapper, outcomeRecorder, snapshotRecorder);

        AgentOutcomeAttribution attribution = service.recompute("run-tool").orElseThrow();

        assertEquals(AgentPrimaryCause.TOOL_UNRELIABLE, attribution.getPrimaryCause());
    }

    @Test
    void shouldRecomputePromptWeakAttribution() {
        AgentOutcomeAttributionMapper mapper = mock(AgentOutcomeAttributionMapper.class);
        AgentOutcomeRecorder outcomeRecorder = mock(AgentOutcomeRecorder.class);
        AgentRunStrategySnapshotRecorder snapshotRecorder = mock(AgentRunStrategySnapshotRecorder.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(outcomeRecorder.findByRunId("run-prompt")).thenReturn(Optional.of(AgentRunOutcome.builder()
                .runId("run-prompt")
                .sessionId("run-prompt")
                .scene("doctor")
                .outcomeLabel(AgentOutcomeLabel.FAILED_PROTOCOL_ERROR)
                .errorMessage("协议校验失败")
                .build()));
        DefaultAgentOutcomeAttributionService service =
                new DefaultAgentOutcomeAttributionService(mapper, outcomeRecorder, snapshotRecorder);

        AgentOutcomeAttribution attribution = service.recompute("run-prompt").orElseThrow();

        assertEquals(AgentPrimaryCause.PROMPT_WEAK, attribution.getPrimaryCause());
    }

    @Test
    void shouldRecomputeInsufficientEvidenceAttribution() {
        AgentOutcomeAttributionMapper mapper = mock(AgentOutcomeAttributionMapper.class);
        AgentOutcomeRecorder outcomeRecorder = mock(AgentOutcomeRecorder.class);
        AgentRunStrategySnapshotRecorder snapshotRecorder = mock(AgentRunStrategySnapshotRecorder.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(outcomeRecorder.findByRunId("run-evidence")).thenReturn(Optional.of(AgentRunOutcome.builder()
                .runId("run-evidence")
                .sessionId("run-evidence")
                .scene("doctor")
                .outcomeLabel(AgentOutcomeLabel.MANUAL_REVIEW_REQUIRED)
                .evidenceSufficient(Boolean.FALSE)
                .build()));
        when(snapshotRecorder.findByRunId("run-evidence")).thenReturn(Optional.of(AgentRunStrategySnapshot.builder()
                .runId("run-evidence")
                .scene("doctor")
                .memoryRetrievalCount(0)
                .toolSequence(List.of())
                .build()));
        DefaultAgentOutcomeAttributionService service =
                new DefaultAgentOutcomeAttributionService(mapper, outcomeRecorder, snapshotRecorder);

        AgentOutcomeAttribution attribution = service.recompute("run-evidence").orElseThrow();

        assertEquals(AgentPrimaryCause.INSUFFICIENT_EVIDENCE, attribution.getPrimaryCause());
    }
}
