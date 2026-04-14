package com.medical.report.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MedicalReportHistory {

    private Long id;
    private String patientName;
    private String age;
    private String gender;
    private String questionnaireData;
    private String healthAdvice;
    private String riskAssessment;
    private String medicalGuidance;
    private String reportDate;
    private LocalDateTime createdAt;
    private String pdfUrl;
    private String pdfContent;
}
