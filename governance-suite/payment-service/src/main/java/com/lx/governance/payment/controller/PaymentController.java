package com.lx.governance.payment.controller;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.payment.domain.PaymentDto;
import com.lx.governance.payment.domain.PreparePaymentRequest;
import com.lx.governance.payment.service.PaymentAppService;
import org.springframework.web.bind.annotation.*;

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
    public ApiResponse<PaymentDto> confirm(@PathVariable String orderNo) {
        return ApiResponse.ok(appService.confirm(orderNo), Trace.getOrCreate());
    }
}
