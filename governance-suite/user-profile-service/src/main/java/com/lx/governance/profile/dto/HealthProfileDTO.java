package com.lx.governance.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthProfileDTO {
    private Double bmi;
    private String bloodPressure;
    private Double bloodSugar;
    private LocalDate latestAssessmentDate;
    private List<String> healthGoals;

    public static HealthProfileDTO empty() {
        return HealthProfileDTO.builder()
            .healthGoals(List.of())
            .build();
    }
}
