package com.lx.governance.appointment.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ScheduleVO {

    private Long scheduleId;

    private String doctorName;

    private String deptName;

    private String title;

    private LocalDate scheduleDate;

    private String timeSlot;

    private Integer availableQuota;

    private Integer feeCents;
}
