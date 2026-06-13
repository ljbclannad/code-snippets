package com.lx.governance.appointment.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ScheduleVO {

    private Long scheduleId;

    private Long deptId;

    private String deptName;

    private Long doctorId;

    private String doctorName;

    private String title;

    private LocalDate scheduleDate;

    private String timeSlot;

    private Integer totalQuota;

    private Integer availableQuota;

    private Integer feeCents;

    private String status;
}
