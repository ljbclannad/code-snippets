package com.health.agent.model;

public enum AgentStreamEventType {
    THINKING_DELTA,
    THOUGHT,
    ACTION,
    OBSERVATION,
    PENDING_APPROVAL,
    FINAL_ANSWER,
    ERROR,
    DONE
}
