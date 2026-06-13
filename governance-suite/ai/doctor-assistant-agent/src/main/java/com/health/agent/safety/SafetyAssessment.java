package com.health.agent.safety;

/**
 * 医疗安全规则评估结果
 */
public record SafetyAssessment(boolean blocked, String message, String category) {

    public static SafetyAssessment allow() {
        return new SafetyAssessment(false, "", "ALLOW");
    }

    public static SafetyAssessment block(String message) {
        return block("GENERAL", message);
    }

    public static SafetyAssessment block(String category, String message) {
        return new SafetyAssessment(true, message, category);
    }

    public boolean isBlocked() {
        return blocked;
    }
}
