package com.health.agent.learning.service;

import java.util.Optional;

import com.health.agent.learning.model.AgentRunStrategySnapshot;

public interface AgentRunStrategySnapshotRecorder {

    void createOrUpdate(AgentRunStrategySnapshot snapshot);

    Optional<AgentRunStrategySnapshot> findByRunId(String runId);
}
