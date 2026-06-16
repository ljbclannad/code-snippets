package com.health.agent.service;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.health.agent.dto.patient.StructuredTaskResult;
import com.health.agent.safety.MedicalSafetyService;

@Component
public class PatientTaskSafetyService {

    private static final List<String> HIGH_RISK_KEYWORDS = List.of(
        "胸痛", "呼吸困难", "晕厥", "黑便", "便血", "呕血", "昏迷", "抽搐", "自杀", "轻生"
    );
    private static final List<String> MEDICATION_KEYWORDS = List.of(
        "处方", "药物调整", "加量", "减量", "停药", "换药", "司美格鲁肽", "利拉鲁肽", "mg", "毫克"
    );
    private static final List<String> DIAGNOSIS_KEYWORDS = List.of(
        "确诊", "诊断为", "明确诊断", "就是", "肯定是"
    );
    private static final List<String> DOCTOR_ONLY_TERMS = List.of(
        "风险等级", "证据来源", "内部判断", "待审批", "工作台"
    );

    private final MedicalSafetyService medicalSafetyService;

    public PatientTaskSafetyService() {
        this(new MedicalSafetyService());
    }

    public PatientTaskSafetyService(MedicalSafetyService medicalSafetyService) {
        this.medicalSafetyService = medicalSafetyService;
    }

    public SafetyResult sanitize(String patientReply, StructuredTaskResult structuredResult, boolean needsHumanReview) {
        String combined = normalize(patientReply) + " " + normalize(structuredResult != null ? structuredResult.getSummary() : "");

        if (medicalSafetyService.hasEmergencySignal(combined) || containsAny(combined, HIGH_RISK_KEYWORDS)) {
            return SafetyResult.blocked(
                    "MANDATORY_ESCALATION",
                    "目前存在需要尽快人工评估的风险信号，请尽快联系医生或线下就医；若症状明显加重，请及时前往急诊。");
        }

        if (medicalSafetyService.hasPrescriptionRisk(combined)
                || containsAny(combined, MEDICATION_KEYWORDS)
                || containsAny(combined, DIAGNOSIS_KEYWORDS)) {
            return SafetyResult.blocked(
                    "DIRECT_MEDICAL_ADVICE_BLOCK",
                    "目前不适合在线直接给出处方、药物调整或诊断结论，建议尽快联系医生进行进一步评估。");
        }

        int evidenceCount = structuredResult == null || structuredResult.getSupportingEvidence() == null
                ? 0 : structuredResult.getSupportingEvidence().size();
        if (evidenceCount < 2) {
            return SafetyResult.blocked(
                    "INSUFFICIENT_EVIDENCE",
                    "目前证据还不够充分，建议先补充近几天症状、监测数据和执行情况，再由医生进一步判断。");
        }

        String sanitizedReply = patientReply == null ? "" : patientReply;
        for (String term : DOCTOR_ONLY_TERMS) {
            sanitizedReply = sanitizedReply.replace(term, "相关评估信息");
        }

        if (needsHumanReview) {
            sanitizedReply = sanitizedReply
                    + " 这条建议也会同步给医生进一步确认，在得到人工评估前请先按保守方案观察。";
        }

        return SafetyResult.allowed(sanitizedReply);
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(keyword -> text.contains(keyword.toLowerCase(Locale.ROOT)));
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    public record SafetyResult(boolean blocked, String category, String reply) {
        public static SafetyResult allowed(String reply) {
            return new SafetyResult(false, null, reply);
        }

        public static SafetyResult blocked(String category, String reply) {
            return new SafetyResult(true, category, reply);
        }
    }
}
