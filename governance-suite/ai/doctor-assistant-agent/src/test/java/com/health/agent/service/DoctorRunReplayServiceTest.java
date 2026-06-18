package com.health.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

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
        repository.save(RunPersistenceSnapshot.builder()
                .run(Run.builder()
                        .runId("run-1")
                        .runType(RunType.TASK)
                        .status(RunStatus.COMPLETED)
                        .build())
                .build());

        DoctorRunReplayService service = new DoctorRunReplayService(new RunReplayService(
                repository, repository, repository, repository, repository));

        assertEquals("run-1", service.getReplayByRunId("run-1").getRunId());
    }

    @Test
    void shouldThrowWhenRunNotFound() {
        InMemoryRunPersistenceRepository repository = new InMemoryRunPersistenceRepository();
        DoctorRunReplayService service = new DoctorRunReplayService(new RunReplayService(
                repository, repository, repository, repository, repository));

        assertThrows(IllegalArgumentException.class, () -> service.getReplayByRunId("missing"));
    }
}
