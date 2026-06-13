package com.lx.governance.appointment.dto;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class AppointmentPaidEvent implements Serializable {
    private String orderNo;
    private Long userId;
    private Long scheduleId;
    private LocalDateTime paidAt;
}
