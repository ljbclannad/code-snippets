package com.lx.governance.appointment.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GrabResultVO {

    private Long scheduleId;

    private LocalDateTime expireAt;
}
