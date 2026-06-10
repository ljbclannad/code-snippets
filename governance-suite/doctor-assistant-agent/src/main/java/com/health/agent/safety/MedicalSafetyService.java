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

    private static final List<String> PRESCRIPTION_KEYWORDS = List.of(
        "处方", "开药", "开具", "用药", "药物", "剂量", "mg", "毫克", "奥利司他", "司美格鲁肽", "利拉鲁肽"
    );

    private static final List<String> MEDICATION_ADJUSTMENT_KEYWORDS = List.of(
        "调整药物", "调整剂量", "加量", "减量", "停药", "换药", "剂量加倍", "增加剂量", "减少剂量"
    );

    private static final List<String> FOLLOW_UP_DELAY_KEYWORDS = List.of(
        "暂缓复诊", "无需复诊", "不用复诊", "继续线上观察", "继续观察", "不必就医", "不用就医"
    );

    private static final List<String> MEDICAL_ADVICE_KEYWORDS = List.of(
        "继续当前减重方案", "调整减重方案", "治疗方案", "医学建议", "诊疗建议"
    );

    private static final List<String> HIGH_RISK_TOOL_NAMES = List.of(
        "draft_weight_management_plan", "recommend_medical_review"
    );

    public SafetyAssessment assessGoal(String goal) {
        if (containsAny(goal, EMERGENCY_KEYWORDS)) {
            return SafetyAssessment.block(
                "EMERGENCY",
                "检测到减重管理中的高危症状或心理危机信号，建议立即线下就医或急诊评估，并尽快转人工医生处理。"
            );
        }
        if (containsAny(goal, MEDICATION_ADJUSTMENT_KEYWORDS) || containsAny(goal, PRESCRIPTION_KEYWORDS)) {
            return SafetyAssessment.block(
                "MEDICATION_ADJUSTMENT",
                "检测到处方或药物调整请求，Agent 不得自动生成药物调整结论，请转人工医生基于完整病史、禁忌证和线下评估处理。"
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
                "MEDICAL_ADVICE",
                "SAFETY_BLOCK: 当前减重管理高风险建议缺少患者档案或体重趋势证据，请先补充基础资料和近30天体重变化后再继续。"
            );
        }

        return SafetyAssessment.allow();
    }

    public SafetyAssessment assessFinalAnswer(StructuredFinalAnswer finalAnswer, List<Step> history) {
        String summary = finalAnswer != null ? finalAnswer.getSummary() : "";
        if (containsAny(summary, EMERGENCY_KEYWORDS)) {
            return SafetyAssessment.block(
                "EMERGENCY",
                "检测到减重管理场景中的高危症状描述，建议立即停止线上建议，尽快转人工医生处理，并建议患者立即线下就医或急诊评估。"
            );
        }

        String combinedAnswer = combineFinalAnswerText(finalAnswer);
        if (containsAny(combinedAnswer, PRESCRIPTION_KEYWORDS) || containsAny(combinedAnswer, MEDICATION_ADJUSTMENT_KEYWORDS)) {
            return SafetyAssessment.block(
                "PRESCRIPTION",
                "检测到处方或用药调整内容，Agent 只能提示医生人工复核，不得直接输出具体处方、药名或剂量调整建议。"
            );
        }

        if (containsAny(combinedAnswer, FOLLOW_UP_DELAY_KEYWORDS)) {
            return SafetyAssessment.block(
                "FOLLOW_UP",
                "检测到可能延误线下复诊或就医的建议，减重管理场景中出现不适、风险或证据不足时应提示复诊或转人工医生处理。"
            );
        }

        boolean hasWeightEvidence = containsObservation(history, "\"weight\"")
            || containsObservation(history, "\"date\"");
        boolean hasRiskyDirective = containsAny(combinedAnswer, MEDICAL_ADVICE_KEYWORDS);
        if (hasRiskyDirective && !hasWeightEvidence) {
            return SafetyAssessment.block(
                "MEDICAL_ADVICE",
                "当前减重建议证据不足，建议先补充近30天体重趋势、饮食运动执行情况和不适症状，再由医生评估是否调整方案。"
            );
        }

        if (finalAnswer != null && "HIGH".equalsIgnoreCase(finalAnswer.getRiskLevel())) {
            int evidenceCount = finalAnswer.getSupportingEvidence() == null ? 0 : finalAnswer.getSupportingEvidence().size();
            int sourceCount = finalAnswer.getEvidenceSources() == null ? 0 : finalAnswer.getEvidenceSources().size();
            if (evidenceCount < 2) {
                return SafetyAssessment.block(
                    "MEDICAL_ADVICE",
                    "高风险建议至少带 2 条 supportingEvidence，当前证据不足，请补充更多事实依据后再继续。"
                );
            }
            if (sourceCount < 1) {
                return SafetyAssessment.block(
                    "MEDICAL_ADVICE",
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

    private String combineFinalAnswerText(StructuredFinalAnswer finalAnswer) {
        if (finalAnswer == null) {
            return "";
        }

        return String.join(" ",
            nullToEmpty(finalAnswer.getSummary()),
            join(finalAnswer.getKeyFindings()),
            join(finalAnswer.getRecommendedActions()),
            join(finalAnswer.getUncertainties()),
            join(finalAnswer.getSupportingEvidence()),
            join(finalAnswer.getInferences())
        );
    }

    private String join(List<String> values) {
        return values == null ? "" : String.join(" ", values);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)));
    }
}
