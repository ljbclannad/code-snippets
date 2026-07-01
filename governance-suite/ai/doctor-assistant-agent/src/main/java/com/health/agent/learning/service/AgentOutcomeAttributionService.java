package com.health.agent.learning.service;

import java.util.Optional;

import com.health.agent.learning.model.AgentOutcomeAttribution;

public interface AgentOutcomeAttributionService {

    void createOrUpdate(AgentOutcomeAttribution attribution);

    Optional<AgentOutcomeAttribution> findByRunId(String runId);

    Optional<AgentOutcomeAttribution> recompute(String runId);
}
