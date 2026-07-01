package com.health.agent.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.health.agent.learning.model.AgentOutcomeAttribution;
import com.health.agent.learning.service.AgentOutcomeAttributionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/internal/agent/outcomes")
@Tag(name = "Agent Outcome Attribution", description = "Agent outcome attribution internal operations")
public class AgentOutcomeAttributionController {

    private final AgentOutcomeAttributionService attributionService;

    public AgentOutcomeAttributionController(AgentOutcomeAttributionService attributionService) {
        this.attributionService = attributionService;
    }

    @PostMapping("/recompute/{runId}")
    @Operation(summary = "按 runId 重算 outcome attribution")
    public ResponseEntity<Map<String, Object>> recompute(
            @Parameter(description = "平台 runId") @PathVariable("runId") String runId) {
        return attributionService.recompute(runId)
                .map(this::toMap)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> toMap(AgentOutcomeAttribution attribution) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", attribution.getRunId());
        payload.put("sessionId", attribution.getSessionId());
        payload.put("scene", attribution.getScene());
        payload.put("taskType", attribution.getTaskType());
        payload.put("primaryCause", attribution.getPrimaryCause() != null ? attribution.getPrimaryCause().name() : null);
        payload.put("secondaryCauses", attribution.getSecondaryCauses());
        payload.put("confidence", attribution.getConfidence());
        payload.put("evidenceSummary", attribution.getEvidenceSummary());
        return payload;
    }
}
