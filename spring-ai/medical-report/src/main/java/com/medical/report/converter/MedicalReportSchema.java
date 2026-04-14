package com.medical.report.converter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.medical.report.model.MedicalReportResponse;
import lombok.Data;

import java.util.List;

/**
 * Structured output schema for AI response parsing
 */
@Data
public class MedicalReportSchema {

    @JsonProperty("healthAdvice")
    @JsonPropertyDescription("健康建议列表，包含饮食、运动、生活方式等建议")
    private List<String> healthAdvice;

    @JsonProperty("riskAssessment")
    @JsonPropertyDescription("风险评估列表，包含识别的健康风险和预防措施")
    private List<String> riskAssessment;

    @JsonProperty("medicalGuidance")
    @JsonPropertyDescription("就医指导列表，包含推荐科室、紧急程度、建议检查项目")
    private List<String> medicalGuidance;

    public MedicalReportResponse toResponse(String patientName, String reportDate) {
        return MedicalReportResponse.builder()
                .patientName(patientName)
                .reportDate(reportDate)
                .healthAdvice(healthAdvice)
                .riskAssessment(riskAssessment)
                .medicalGuidance(medicalGuidance)
                .build();
    }
}
