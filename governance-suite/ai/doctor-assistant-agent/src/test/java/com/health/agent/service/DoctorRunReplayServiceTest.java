package com.health.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.health.agent.learning.model.AgentRunOutcome;
import com.health.agent.learning.model.AgentRunStrategySnapshot;
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

        DoctorRunReplayService service = new DoctorRunReplayService(new RunReplayService(
                repository, repository, repository, repository, repository), strategySnapshotRecorder, outcomeRecorder);

        assertEquals("run-1", service.getReplayByRunId("run-1").getRunId());
        assertEquals("doctor", service.getReplayByRunId("run-1").getStrategySnapshot().get("scene"));
        assertEquals("SUCCESS", service.getReplayByRunId("run-1").getOutcome().get("finalStatus"));
    }

    @Test
    void shouldThrowWhenRunNotFound() {
        InMemoryRunPersistenceRepository repository = new InMemoryRunPersistenceRepository();
        AgentRunStrategySnapshotRecorder strategySnapshotRecorder = mock(AgentRunStrategySnapshotRecorder.class);
        AgentOutcomeRecorder outcomeRecorder = mock(AgentOutcomeRecorder.class);
        DoctorRunReplayService service = new DoctorRunReplayService(new RunReplayService(
                repository, repository, repository, repository, repository), strategySnapshotRecorder, outcomeRecorder);

        assertThrows(IllegalArgumentException.class, () -> service.getReplayByRunId("missing"));
    }
}
