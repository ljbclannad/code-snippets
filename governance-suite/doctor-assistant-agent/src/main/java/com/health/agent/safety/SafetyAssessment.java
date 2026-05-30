package com.health.agent.safety;

/**
 * 医疗安全规则评估结果
 */
public record SafetyAssessment(boolean blocked, String message) {

    public static SafetyAssessment allow() {
        return new SafetyAssessment(false, "");
    }

    public static SafetyAssessment block(String message) {
        return new SafetyAssessment(true, message);
    }

    public boolean isBlocked() {
        return blocked;
    }
}
