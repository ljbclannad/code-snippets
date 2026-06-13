package com.health.agent.model;

public enum AgentStreamEventType {
    SUBAGENT_STARTED,
    SUBAGENT_FINISHED,
    CONTEXT_UPDATED,
    THINKING_DELTA,
    THOUGHT,
    ACTION,
    OBSERVATION,
    PENDING_APPROVAL,
    FINAL_ANSWER,
    ERROR,
    DONE
}
