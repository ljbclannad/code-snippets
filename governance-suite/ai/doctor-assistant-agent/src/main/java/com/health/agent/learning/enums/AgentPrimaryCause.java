package com.health.agent.learning.enums;

/**
 * Agent 结果主归因原因。
 */
public enum AgentPrimaryCause {
    RUNTIME_TIMEOUT,
    TOOL_UNRELIABLE,
    INSUFFICIENT_EVIDENCE,
    PROMPT_WEAK,
    ROUTING_WEAK,
    MEMORY_NOISE,
    MEMORY_MISSING,
    HUMAN_REJECTED,
    UNKNOWN
}
