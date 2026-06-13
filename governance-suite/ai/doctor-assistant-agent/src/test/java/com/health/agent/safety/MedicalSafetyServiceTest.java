package com.health.agent.safety;

import com.health.agent.model.ActionDecision;
import com.health.agent.model.Step;
import com.health.agent.model.StructuredFinalAnswer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MedicalSafetyServiceTest {

    private final MedicalSafetyService safetyService = new MedicalSafetyService();

    @Test
    void shouldBlockEmergencyWeightManagementGoal() {
        SafetyAssessment assessment = safetyService.assessGoal(
            "患者减重期间出现胸痛和呼吸困难，是否继续当前方案"
        );

        assertTrue(assessment.isBlocked());
        assertEquals("EMERGENCY", assessment.category());
        assertTrue(assessment.message().contains("立即线下就医"));
    }

    @Test
    void shouldBlockMedicationAdjustmentGoal() {
        SafetyAssessment assessment = safetyService.assessGoal(
            "患者想把司美格鲁肽剂量加倍，帮我直接给调整方案"
        );

        assertTrue(assessment.isBlocked());
        assertEquals("MEDICATION_ADJUSTMENT", assessment.category());
        assertTrue(assessment.message().contains("不得自动生成药物调整结论"));
    }

    @Test
    void shouldRequireManualReviewForPrescriptionFinalAnswer() {
        SafetyAssessment assessment = safetyService.assessFinalAnswer(
            StructuredFinalAnswer.builder()
                .summary("建议开具奥利司他并调整减重药物剂量。")
                .riskLevel("MEDIUM")
                .recommendedActions(List.of("开具奥利司他", "调整药物剂量"))
                .supportingEvidence(List.of("患者近期体重下降变慢", "自述食欲增加"))
                .evidenceSources(List.of("WEIGHT_HISTORY", "CHAT_HISTORY"))
                .build(),
            List.of(Step.observation("{\"weight\":80.0,\"date\":\"2026-05-30\"}"))
        );

        assertTrue(assessment.isBlocked());
        assertEquals("PRESCRIPTION", assessment.category());
        assertTrue(assessment.message().contains("处方或用药调整"));
    }

    @Test
    void shouldRequireOfflineReviewForFollowUpDelayFinalAnswer() {
        SafetyAssessment assessment = safetyService.assessFinalAnswer(
            StructuredFinalAnswer.builder()
                .summary("患者出现明显不适，但可以暂缓复诊，继续线上观察。")
                .riskLevel("MEDIUM")
                .recommendedActions(List.of("暂缓复诊", "继续线上观察"))
                .supportingEvidence(List.of("患者描述不适", "近期体重波动"))
                .evidenceSources(List.of("CHAT_HISTORY"))
                .build(),
            List.of(Step.observation("{\"weight\":80.0,\"date\":\"2026-05-30\"}"))
        );

        assertTrue(assessment.isBlocked());
        assertEquals("FOLLOW_UP", assessment.category());
        assertTrue(assessment.message().contains("复诊"));
    }

    @Test
    void shouldBlockHighRiskActionWhenWeightEvidenceIsInsufficient() {
        ActionDecision action = ActionDecision.builder()
            .toolName("draft_weight_management_plan")
            .input(Map.of(
                "customerId", "C001",
                "recommendations", List.of("调整减重药物")
            ))
            .reason("需要给出处方建议")
            .build();

        SafetyAssessment assessment = safetyService.assessAction(
            action,
            List.of(
                Step.thought("先直接给出处方建议")
            )
        );

        assertTrue(assessment.isBlocked());
        assertTrue(assessment.message().contains("缺少患者档案或体重趋势证据"));
    }

    @Test
    void shouldDowngradeUnsafeFinalAnswerToReferralMessage() {
        SafetyAssessment assessment = safetyService.assessFinalAnswer(
            StructuredFinalAnswer.builder()
                .summary("患者最近在减肥药使用后出现头晕、胸闷和心悸，建议继续观察并保持当前减重方案。")
                .riskLevel("MEDIUM")
                .recommendedActions(List.of("继续观察并保持当前减重方案"))
                .supportingEvidence(List.of("患者描述出现胸闷和心悸"))
                .evidenceSources(List.of("CHAT_HISTORY"))
                .inferences(List.of("可能与减肥药有关"))
                .build(),
            List.of(
                Step.thought("已经有足够信息")
            )
        );

        assertTrue(assessment.isBlocked());
        assertFalse(assessment.message().contains("继续观察并保持当前减重方案"));
        assertTrue(assessment.message().contains("建议立即停止线上建议"));
    }

    @Test
    void shouldBlockHighRiskStructuredAnswerWhenSupportingEvidenceIsInsufficient() {
        SafetyAssessment assessment = safetyService.assessFinalAnswer(
            StructuredFinalAnswer.builder()
                .summary("建议调整减重管理方案并转人工医学复核。")
                .riskLevel("HIGH")
                .recommendedActions(List.of("立即调整减重管理方案"))
                .supportingEvidence(List.of("近两周体重未下降"))
                .evidenceSources(List.of("WEIGHT_HISTORY"))
                .inferences(List.of("可能进入停滞期"))
                .build(),
            List.of(Step.thought("需要给出高风险建议"))
        );

        assertTrue(assessment.isBlocked());
        assertTrue(assessment.message().contains("至少带 2 条 supportingEvidence"));
    }
}
