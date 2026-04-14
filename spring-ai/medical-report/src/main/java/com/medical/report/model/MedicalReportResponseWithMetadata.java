package com.medical.report.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicalReportResponseWithMetadata {
    private MedicalReportResponse report;
    private TokenUsageInfo tokenUsage;
}
