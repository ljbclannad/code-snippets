package com.lx.governance.order.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.auth.AuthHeaders;
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

    @SentinelResource(value = "order:create", blockHandler = "handleCreateBlocked")
    @PostMapping("/order")
    public ApiResponse<OrderDto> create(@RequestBody CreateOrderRequest req,
                                        @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long authenticatedUserId,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (authenticatedUserId != null) {
            req.setCustomerId(String.valueOf(authenticatedUserId));
        }
        OrderDto dto = appService.create(req, idempotencyKey);
        return ApiResponse.ok(dto, Trace.getOrCreate());
    }

    @SentinelResource(value = "order:create-v2", blockHandler = "handleCreateV2Blocked")
    @PostMapping("/order/v2")
    public ApiResponse<Map<String, String>> createV2(@RequestBody CreateOrderRequest req,
                                                     @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long authenticatedUserId,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String reqId) {
        if (authenticatedUserId != null) {
            req.setCustomerId(String.valueOf(authenticatedUserId));
        }
        return ApiResponse.ok(appService.createV2(req, reqId), Trace.getOrCreate());
    }

    public ApiResponse<OrderDto> handleCreateBlocked(CreateOrderRequest req,
                                                     Long authenticatedUserId,
                                                     String idempotencyKey,
                                                     BlockException ex) {
        return ApiResponse.fail("ORDER_RATE_LIMITED", "Order create rate limit exceeded", Trace.getOrCreate());
    }

    public ApiResponse<Map<String, String>> handleCreateV2Blocked(CreateOrderRequest req,
                                                                  Long authenticatedUserId,
                                                                  String reqId,
                                                                  BlockException ex) {
        return ApiResponse.fail("ORDER_RATE_LIMITED", "Order create rate limit exceeded", Trace.getOrCreate());
    }

    @GetMapping("/order/{orderNo}")
    public ApiResponse<OrderDto> get(@PathVariable String orderNo,
                                     @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long authenticatedUserId) {
        return ApiResponse.ok(appService.get(orderNo, authenticatedUserId), Trace.getOrCreate());
    }

    @PostMapping("/order/payment/callback/{orderNo}")
    public ApiResponse<Map<String, String>> paymentCallback(@PathVariable String orderNo) {
        return ApiResponse.ok(appService.paymentCallbackPass(orderNo), Trace.getOrCreate());
    }
}
