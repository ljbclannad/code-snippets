package com.lx.governance.appointment.controller;

import com.lx.governance.appointment.dto.GrabRequest;
import com.lx.governance.appointment.dto.GrabResultVO;
import com.lx.governance.appointment.service.AppointmentService;
import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/appointment")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping("/grab")
    public ApiResponse<GrabResultVO> grabAppointment(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("Idempotency-Key") String requestId,
            @RequestBody GrabRequest request) {

        try {
            GrabResultVO result = appointmentService.grabAppointment(
                userId,
                request.getScheduleId(),
                requestId
            );

            return ApiResponse.ok(result, Trace.getOrCreate());

        } catch (RuntimeException e) {
            String code = e.getMessage();
            String message;

            switch (code) {
                case "SOLD_OUT":
                    message = "号源已抢完";
                    break;
                case "ALREADY_GRABBED":
                    message = "您已抢过该排班";
                    break;
                case "DUPLICATE_REQUEST":
                    message = "重复请求";
                    break;
                default:
                    message = "抢号失败";
            }

            return ApiResponse.fail(code, message, Trace.getOrCreate());
        }
    }
}
