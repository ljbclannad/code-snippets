package com.medical.report.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicalReportResponse {
    private String patientName;
    private String reportDate;
    private List<String> healthAdvice;
    private List<String> riskAssessment;
    private List<String> medicalGuidance;
}
