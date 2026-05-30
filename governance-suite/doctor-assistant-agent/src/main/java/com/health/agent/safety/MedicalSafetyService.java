package com.health.agent.safety;

import com.health.agent.model.ActionDecision;
import com.health.agent.model.Step;
import com.health.agent.model.StepType;
import com.health.agent.model.StructuredFinalAnswer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 减重管理场景下的医疗安全规则。
 */
@Component
public class MedicalSafetyService {

    private static final List<String> EMERGENCY_KEYWORDS = List.of(
        "胸痛", "呼吸困难", "胸闷", "心悸", "晕厥", "昏迷", "抽搐", "便血", "呕血", "自杀", "轻生"
    );

    private static final List<String> HIGH_RISK_TOOL_NAMES = List.of(
        "draft_weight_management_plan", "recommend_medical_review"
    );

    public SafetyAssessment assessGoal(String goal) {
        if (containsAny(goal, EMERGENCY_KEYWORDS)) {
            return SafetyAssessment.block(
                "检测到减重管理中的高危症状或心理危机信号，建议立即线下就医或急诊评估，并尽快转人工医生处理。"
            );
        }
        return SafetyAssessment.allow();
    }

    public SafetyAssessment assessAction(ActionDecision action, List<Step> history) {
        if (!HIGH_RISK_TOOL_NAMES.contains(action.getToolName())) {
            return SafetyAssessment.allow();
        }

        boolean hasProfileEvidence = containsObservation(history, "customerId")
            || containsObservation(history, "\"bmi\"");
        boolean hasWeightEvidence = containsObservation(history, "\"weight\"")
            || containsObservation(history, "\"date\"");

        if (!hasProfileEvidence || !hasWeightEvidence) {
            return SafetyAssessment.block(
                "SAFETY_BLOCK: 当前减重管理高风险建议缺少患者档案或体重趋势证据，请先补充基础资料和近30天体重变化后再继续。"
            );
        }

        return SafetyAssessment.allow();
    }

    public SafetyAssessment assessFinalAnswer(StructuredFinalAnswer finalAnswer, List<Step> history) {
        String summary = finalAnswer != null ? finalAnswer.getSummary() : "";
        if (containsAny(summary, EMERGENCY_KEYWORDS)) {
            return SafetyAssessment.block(
                "检测到减重管理场景中的高危症状描述，建议立即停止线上建议，尽快转人工医生处理，并建议患者立即线下就医或急诊评估。"
            );
        }

        boolean hasWeightEvidence = containsObservation(history, "\"weight\"")
            || containsObservation(history, "\"date\"");
        boolean hasRiskyDirective = containsAny(summary, List.of("继续当前减重方案", "继续观察", "调整减重药物", "处方"));
        if (hasRiskyDirective && !hasWeightEvidence) {
            return SafetyAssessment.block(
                "当前减重建议证据不足，建议先补充近30天体重趋势、饮食运动执行情况和不适症状，再由医生评估是否调整方案。"
            );
        }

        if (finalAnswer != null && "HIGH".equalsIgnoreCase(finalAnswer.getRiskLevel())) {
            int evidenceCount = finalAnswer.getSupportingEvidence() == null ? 0 : finalAnswer.getSupportingEvidence().size();
            int sourceCount = finalAnswer.getEvidenceSources() == null ? 0 : finalAnswer.getEvidenceSources().size();
            if (evidenceCount < 2) {
                return SafetyAssessment.block(
                    "高风险建议至少带 2 条 supportingEvidence，当前证据不足，请补充更多事实依据后再继续。"
                );
            }
            if (sourceCount < 1) {
                return SafetyAssessment.block(
                    "高风险建议缺少 evidenceSources，请补充真实数据来源后再继续。"
                );
            }
        }

        return SafetyAssessment.allow();
    }

    public StructuredFinalAnswer buildSafetyStructuredAnswer(String message) {
        return StructuredFinalAnswer.builder()
            .summary(message)
            .keyFindings(List.of("触发减重管理安全规则"))
            .riskLevel("HIGH")
            .recommendedActions(List.of("立即转人工医生处理", "建议患者尽快线下就医或急诊评估"))
            .needsAttention(true)
            .uncertainties(List.of("线上信息不足以支持继续给出常规减重建议"))
            .supportingEvidence(List.of("触发安全规则"))
            .evidenceSources(List.of("SAFETY_RULE"))
            .inferences(List.of("当前输出不满足安全约束"))
            .build();
    }

    public StructuredFinalAnswer buildDegradedStructuredAnswer(
        String summary,
        String riskLevel,
        String runtimeEvidence,
        List<String> recommendedActions,
        List<String> uncertainties
    ) {
        return StructuredFinalAnswer.builder()
            .summary(summary)
            .keyFindings(List.of("系统进入保守降级模式"))
            .riskLevel(riskLevel)
            .recommendedActions(recommendedActions)
            .needsAttention(true)
            .uncertainties(uncertainties)
            .supportingEvidence(List.of(runtimeEvidence))
            .evidenceSources(List.of("SYSTEM_RUNTIME"))
            .inferences(List.of("当前无法安全完成自动分析，需要人工接管或补充数据"))
            .build();
    }

    private boolean containsObservation(List<Step> history, String keyword) {
        return history.stream()
            .filter(step -> step.getType() == StepType.OBSERVATION)
            .map(Step::getContent)
            .anyMatch(content -> content != null && content.contains(keyword));
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)));
    }
}
