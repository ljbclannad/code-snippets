package com.health.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.health.agent.learning.enums.AgentPrimaryCause;
import com.health.agent.learning.model.AgentOutcomeAttribution;
import com.health.agent.learning.service.AgentOutcomeAttributionService;

class AgentOutcomeAttributionControllerTest {

    @Test
    void shouldReturnRecomputedAttribution() {
        AgentOutcomeAttributionService service = mock(AgentOutcomeAttributionService.class);
        AgentOutcomeAttributionController controller = new AgentOutcomeAttributionController(service);
        when(service.recompute("run-1")).thenReturn(Optional.of(AgentOutcomeAttribution.builder()
                .runId("run-1")
                .primaryCause(AgentPrimaryCause.RUNTIME_TIMEOUT)
                .confidence(0.95D)
                .evidenceSummary(Map.of("errorCode", "TIMEOUT"))
                .build()));

        ResponseEntity<Map<String, Object>> response = controller.recompute("run-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("run-1", response.getBody().get("runId"));
        assertEquals("RUNTIME_TIMEOUT", response.getBody().get("primaryCause"));
    }

    @Test
    void shouldReturnNotFoundWhenRunMissing() {
        AgentOutcomeAttributionService service = mock(AgentOutcomeAttributionService.class);
        AgentOutcomeAttributionController controller = new AgentOutcomeAttributionController(service);
        when(service.recompute("missing")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.recompute("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
