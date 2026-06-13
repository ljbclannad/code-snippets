package com.lx.governance.profile.entity;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class HealthAssessmentEntity {
    private Long id;
    private String customerId;
    private Double bmi;
    private String bloodPressure;
    private Double bloodSugar;
    private LocalDate assessmentDate;
    private String healthGoals;
    private LocalDateTime createTime;
}
