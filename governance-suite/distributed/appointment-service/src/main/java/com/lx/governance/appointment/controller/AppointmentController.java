package com.lx.governance.appointment.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.appointment.dto.CancelRequest;
import com.lx.governance.appointment.dto.GrabRequest;
import com.lx.governance.appointment.dto.GrabResultVO;
import com.lx.governance.appointment.dto.OrderVO;
import com.lx.governance.appointment.entity.AppointmentOrder;
import com.lx.governance.appointment.mapper.AppointmentOrderMapper;
import com.lx.governance.appointment.service.AppointmentService;
import com.lx.governance.appointment.service.PaymentService;
import com.lx.governance.appointment.service.CancelService;
import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/appointment")
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final PaymentService paymentService;
    private final CancelService cancelService;
    private final AppointmentOrderMapper orderMapper;

    public AppointmentController(
            AppointmentService appointmentService,
            PaymentService paymentService,
            CancelService cancelService,
            AppointmentOrderMapper orderMapper) {
        this.appointmentService = appointmentService;
        this.paymentService = paymentService;
        this.cancelService = cancelService;
        this.orderMapper = orderMapper;
    }

    @PostMapping("/grab")
    public ApiResponse<GrabResultVO> grabAppointment(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("Idempotency-Key") String requestId,
            @RequestBody GrabRequest request) {

        GrabResultVO result = appointmentService.grabAppointment(
            userId,
            request.getScheduleId(),
            requestId
        );

        return ApiResponse.ok(result, Trace.getOrCreate());
    }

    @PostMapping("/pay/{orderNo}")
    public ApiResponse<Void> payOrder(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String orderNo) {

        paymentService.payOrder(userId, orderNo);
        return ApiResponse.ok(null, Trace.getOrCreate());
    }

    @PostMapping("/cancel")
    public ApiResponse<Void> cancelOrder(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody @Valid CancelRequest request) {

        cancelService.cancelOrder(userId, request.getOrderNo(), request.getReason());
        return ApiResponse.ok(null, Trace.getOrCreate());
    }

    @GetMapping("/order/{orderNo}")
    public ApiResponse<OrderVO> getOrder(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String orderNo) {

        AppointmentOrder order = orderMapper.selectOne(
            new LambdaQueryWrapper<AppointmentOrder>()
                .eq(AppointmentOrder::getOrderNo, orderNo)
        );

        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }

        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该订单");
        }

        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order, orderVO);

        return ApiResponse.ok(orderVO, Trace.getOrCreate());
    }
}
