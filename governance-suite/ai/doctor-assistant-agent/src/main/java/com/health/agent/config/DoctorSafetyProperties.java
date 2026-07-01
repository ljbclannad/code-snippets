package com.health.agent.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * doctor 场景安全与阈值配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "doctor.agent.safety")
public class DoctorSafetyProperties {

    private WeightPlateau weightPlateau = new WeightPlateau();

    private Rules rules = new Rules();

    private Privacy privacy = new Privacy();

    private EvidenceRetention evidenceRetention = new EvidenceRetention();

    @Data
    public static class WeightPlateau {
        private int windowDays = 14;
        private double maxWeightChangeKg = 0.5d;
        private int recentTrendDays = 7;
        private double recentDownwardThresholdKg = 0.2d;
    }

    @Data
    public static class Rules {
        private int highRiskMinSupportingEvidence = 2;
        private int highRiskMinEvidenceSources = 1;
        private List<String> emergencyKeywords = new ArrayList<>(List.of(
                "胸痛", "呼吸困难", "胸闷", "心悸", "晕厥", "昏迷", "抽搐", "便血", "呕血", "自杀", "轻生"));
        private List<String> prescriptionKeywords = new ArrayList<>(List.of(
                "处方", "开药", "开具", "用药", "药物", "剂量", "mg", "毫克", "奥利司他", "司美格鲁肽", "利拉鲁肽"));
        private List<String> medicationAdjustmentKeywords = new ArrayList<>(List.of(
                "调整药物", "调整剂量", "加量", "减量", "停药", "换药", "剂量加倍", "增加剂量", "减少剂量"));
        private List<String> followUpDelayKeywords = new ArrayList<>(List.of(
                "暂缓复诊", "无需复诊", "不用复诊", "继续线上观察", "继续观察", "不必就医", "不用就医"));
        private List<String> medicalAdviceKeywords = new ArrayList<>(List.of(
                "继续当前减重方案", "调整减重方案", "治疗方案", "医学建议", "诊疗建议"));
        private List<String> highRiskToolNames = new ArrayList<>(List.of(
                "draft_weight_management_plan", "recommend_medical_review"));
    }

    @Data
    public static class Privacy {
        private boolean enabled = true;
        private List<String> nameKeys = new ArrayList<>(List.of(
                "name", "patientName", "realName", "fullName"));
        private List<String> phoneKeys = new ArrayList<>(List.of(
                "phone", "mobile", "telephone", "contactPhone", "contactMobile"));
        private List<String> idCardKeys = new ArrayList<>(List.of(
                "idCard", "idNo", "idNumber", "identityCard", "nationalId"));
        private List<String> addressKeys = new ArrayList<>(List.of(
                "address", "homeAddress", "detailAddress", "fullAddress", "contactAddress"));
    }

    @Data
    public static class EvidenceRetention {
        private boolean enabled = true;
        private int maxRecords = 12;
        private int maxPayloadRecords = 4;
        private int maxSummaryChars = 200;
        private boolean keepAuditOnlyPayload = false;
    }
}
