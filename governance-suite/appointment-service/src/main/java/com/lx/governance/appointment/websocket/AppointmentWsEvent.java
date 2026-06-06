package com.lx.governance.appointment.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentWsEvent {
    private String type;
    private long timestamp;
    private Object payload;
}
