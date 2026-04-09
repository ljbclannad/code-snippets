package com.lx.governance.payment.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.payment.domain.PaymentDto;
import com.lx.governance.payment.domain.PreparePaymentRequest;
import com.lx.governance.payment.service.PaymentAppService;

@RestController
@RequestMapping("/")
public class PaymentController {

    private final PaymentAppService appService;

    public PaymentController(PaymentAppService appService) {
        this.appService = appService;
    }

    @PostMapping("/prepare")
    public ApiResponse<PaymentDto> prepare(@RequestBody PreparePaymentRequest req) {
        return ApiResponse.ok(appService.prepare(req), Trace.getOrCreate());
    }

    @PostMapping("/confirm/{orderNo}")
    public ApiResponse<PaymentDto> confirm(@RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
                                           @PathVariable String orderNo) {
        return ApiResponse.ok(appService.confirm(userId, orderNo), Trace.getOrCreate());
    }

    @GetMapping("/user/{userId}/payments")
    public ApiResponse<java.util.List<PaymentDto>> listByUserId(@PathVariable Long userId) {
        return ApiResponse.ok(appService.listByUserId(userId), Trace.getOrCreate());
    }
}
