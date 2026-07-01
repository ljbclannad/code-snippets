package com.health.agent.learning.service;

import java.util.Optional;

import com.health.agent.learning.model.AgentRunOutcome;

public interface AgentOutcomeRecorder {

    void createOrUpdate(AgentRunOutcome outcome);

    Optional<AgentRunOutcome> findByRunId(String runId);
}
