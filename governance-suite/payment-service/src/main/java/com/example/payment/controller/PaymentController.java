package com.example.payment.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.example.common.ApiResponse;
import com.example.common.Trace;
import com.example.payment.dto.PaymentPrepareRequest;
import com.example.payment.dto.PaymentPrepareResponse;
import com.example.payment.service.PaymentService;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/pay/prepare")
    @SentinelResource(value = "paymentPrepare", blockHandler = "block", fallback = "fallback")
    public PaymentPrepareResponse prepare(@RequestBody PaymentPrepareRequest req) {
        return paymentService.prepare(req);
    }

    @PostMapping("/pay/confirm/{orderNo}")
    public ApiResponse<?> confirm(@PathVariable String orderNo) {
        paymentService.confirmSuccess(orderNo);
        return ApiResponse.ok(java.util.Map.of("orderNo", orderNo, "status", "SUCCESS"), Trace.getOrCreate());
    }

    public PaymentPrepareResponse block(PaymentPrepareRequest req, BlockException ex) {
        PaymentPrepareResponse r = new PaymentPrepareResponse();
        r.setSuccess(false);
        r.setCode("SENTINEL_BLOCK");
        r.setMessage("blocked: " + ex.getClass().getSimpleName());
        return r;
    }

    public PaymentPrepareResponse fallback(PaymentPrepareRequest req, Throwable t) {
        PaymentPrepareResponse r = new PaymentPrepareResponse();
        r.setSuccess(false);
        r.setCode("PAYMENT_ERROR");
        r.setMessage(t.getMessage());
        return r;
    }
}
