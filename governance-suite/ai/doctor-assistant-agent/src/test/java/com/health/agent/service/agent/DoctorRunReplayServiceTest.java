package com.health.agent.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.health.agent.learning.model.AgentRunOutcome;
import com.health.agent.learning.model.AgentRunStrategySnapshot;
import com.health.agent.learning.model.AgentOutcomeAttribution;
import com.health.agent.learning.enums.AgentPrimaryCause;
import com.health.agent.learning.service.AgentOutcomeAttributionService;
import com.health.agent.learning.service.AgentOutcomeRecorder;
import com.health.agent.learning.service.AgentRunStrategySnapshotRecorder;
import com.lx.governance.agentplatform.model.Run;
import com.lx.governance.agentplatform.model.RunPersistenceSnapshot;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.model.RunType;
import com.lx.governance.agentplatform.runtime.InMemoryRunPersistenceRepository;
import com.lx.governance.agentplatform.runtime.RunReplayService;

class DoctorRunReplayServiceTest {

    @Test
    void shouldReturnReplayViewByRunId() {
        InMemoryRunPersistenceRepository repository = new InMemoryRunPersistenceRepository();
        AgentRunStrategySnapshotRecorder strategySnapshotRecorder = mock(AgentRunStrategySnapshotRecorder.class);
        AgentOutcomeRecorder outcomeRecorder = mock(AgentOutcomeRecorder.class);
        AgentOutcomeAttributionService attributionService = mock(AgentOutcomeAttributionService.class);
        repository.save(RunPersistenceSnapshot.builder()
                .run(Run.builder()
                        .runId("run-1")
                        .runType(RunType.TASK)
                        .status(RunStatus.COMPLETED)
                        .build())
                .build());
        when(strategySnapshotRecorder.findByRunId("run-1"))
                .thenReturn(java.util.Optional.of(AgentRunStrategySnapshot.builder().runId("run-1").scene("doctor").build()));
        when(outcomeRecorder.findByRunId("run-1"))
                .thenReturn(java.util.Optional.of(AgentRunOutcome.builder().runId("run-1").finalStatus("SUCCESS").build()));
        when(attributionService.findByRunId("run-1"))
                .thenReturn(java.util.Optional.of(AgentOutcomeAttribution.builder()
                        .runId("run-1")
                        .primaryCause(AgentPrimaryCause.PROMPT_WEAK)
                        .build()));

        DoctorRunReplayService service = new DoctorRunReplayService(new RunReplayService(
                repository, repository, repository, repository, repository), strategySnapshotRecorder, outcomeRecorder,
                attributionService);

        assertEquals("run-1", service.getReplayByRunId("run-1").getRunId());
        assertEquals("doctor", service.getReplayByRunId("run-1").getStrategySnapshot().get("scene"));
        assertEquals("SUCCESS", service.getReplayByRunId("run-1").getOutcome().get("finalStatus"));
        assertEquals("PROMPT_WEAK", service.getReplayByRunId("run-1").getAttribution().get("primaryCause"));
    }

    @Test
    void shouldThrowWhenRunNotFound() {
        InMemoryRunPersistenceRepository repository = new InMemoryRunPersistenceRepository();
        AgentRunStrategySnapshotRecorder strategySnapshotRecorder = mock(AgentRunStrategySnapshotRecorder.class);
        AgentOutcomeRecorder outcomeRecorder = mock(AgentOutcomeRecorder.class);
        AgentOutcomeAttributionService attributionService = mock(AgentOutcomeAttributionService.class);
        DoctorRunReplayService service = new DoctorRunReplayService(new RunReplayService(
                repository, repository, repository, repository, repository), strategySnapshotRecorder, outcomeRecorder,
                attributionService);

        assertThrows(IllegalArgumentException.class, () -> service.getReplayByRunId("missing"));
    }
}
