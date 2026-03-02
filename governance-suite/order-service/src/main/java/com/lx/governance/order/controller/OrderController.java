package com.lx.governance.order.controller;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.domain.CreateOrderRequest;
import com.lx.governance.order.domain.OrderDto;
import com.lx.governance.order.service.OrderAppService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/")
public class OrderController {

    private final OrderAppService appService;

    public OrderController(OrderAppService appService) {
        this.appService = appService;
    }

    @PostMapping("/order")
    public ApiResponse<OrderDto> create(@RequestBody CreateOrderRequest req,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        OrderDto dto = appService.create(req, idempotencyKey);
        return ApiResponse.ok(dto, Trace.getOrCreate());
    }

    @PostMapping("/order/v2")
    public ApiResponse<Map<String, String>> createV2(@RequestBody CreateOrderRequest req,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String reqId) {
        return ApiResponse.ok(appService.createV2(req, reqId), Trace.getOrCreate());
    }

    @GetMapping("/order/{orderNo}")
    public ApiResponse<OrderDto> get(@PathVariable String orderNo) {
        return ApiResponse.ok(appService.get(orderNo), Trace.getOrCreate());
    }

    @PostMapping("/order/payment/callback/{orderNo}")
    public ApiResponse<Map<String, String>> paymentCallback(@PathVariable String orderNo) {
        return ApiResponse.ok(appService.paymentCallbackPass(orderNo), Trace.getOrCreate());
    }
}
