package com.example.idgendemo.web;

import com.example.idgendemo.order.CreateOrderRequest;
import com.example.idgendemo.order.Order;
import com.example.idgendemo.order.OrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService svc;

    public OrderController(OrderService svc) {
        this.svc = svc;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody @Valid CreateOrderRequest req) {
        Order o = svc.createOrder(req.userId, req.amount);
        return Map.of(
                "id", o.getId(),
                "userId", o.getUserId(),
                "amount", o.getAmount(),
                "status", o.getStatus(),
                "createTime", o.getCreateTime()
        );
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Order o = svc.findById(id);
        if (o == null) {
            return Map.of("found", false, "id", id);
        }
        return Map.of(
                "found", true,
                "id", o.getId(),
                "userId", o.getUserId(),
                "amount", o.getAmount(),
                "status", o.getStatus(),
                "createTime", o.getCreateTime()
        );
    }
}
