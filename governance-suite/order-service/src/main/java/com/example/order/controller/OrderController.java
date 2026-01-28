package com.example.order.controller;

import com.example.common.ApiResponse;
import com.example.common.Trace;
import com.example.order.model.CreateOrderRequest;
import com.example.order.model.OrderEntity;
import com.example.order.service.IdempotencyService;
import com.example.order.service.OrderService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.Duration;

@RestController
@RequestMapping("/order")
@Validated
public class OrderController {
    private final OrderService orderService;
    private final IdempotencyService idem;

    public OrderController(OrderService orderService, IdempotencyService idem) {
        this.orderService = orderService;
        this.idem = idem;
    }

    @PostMapping
    public ApiResponse<?> create(@RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                                 @Valid @RequestBody CreateOrderRequest req) {
        String traceId = Trace.getOrCreate();
        if (idemKey != null && !idemKey.isBlank()) {
            boolean ok = idem.tryAcquire("createOrder:" + idemKey, Duration.ofMinutes(5));
            if (!ok) {
                return ApiResponse.fail("DUPLICATE_REQUEST", "duplicate create order", traceId);
            }
        }
        String orderNo = orderService.createOrder(req);
        return ApiResponse.ok(java.util.Map.of("orderNo", orderNo), traceId);
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<?> get(@PathVariable String orderNo) {
        String traceId = Trace.getOrCreate();
        OrderEntity o = orderService.get(orderNo);
        if (o == null) {
            return ApiResponse.fail("NOT_FOUND", "order not found", traceId);
        }
        return ApiResponse.ok(o, traceId);
    }
}
