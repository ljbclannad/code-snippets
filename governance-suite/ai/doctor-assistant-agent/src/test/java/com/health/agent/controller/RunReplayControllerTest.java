package com.health.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.health.agent.service.agent.DoctorRunReplayService;
import com.lx.governance.agentplatform.model.RunReplayView;

class RunReplayControllerTest {

    @Test
    void shouldReturnReplayView() {
        DoctorRunReplayService service = mock(DoctorRunReplayService.class);
        RunReplayController controller = new RunReplayController(service);
        RunReplayView replayView = RunReplayView.builder()
                .runId("run-1")
                .build();
        when(service.getReplayByRunId("run-1")).thenReturn(replayView);

        ResponseEntity<RunReplayView> response = controller.replay("run-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("run-1", response.getBody().getRunId());
    }
}
