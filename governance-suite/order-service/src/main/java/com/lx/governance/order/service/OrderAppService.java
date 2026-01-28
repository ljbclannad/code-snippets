package com.lx.governance.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.client.InventoryClient;
import com.lx.governance.order.client.PaymentClient;
import com.lx.governance.order.domain.CreateOrderRequest;
import com.lx.governance.order.domain.InventoryDeductRequest;
import com.lx.governance.order.domain.OrderDto;
import com.lx.governance.order.domain.PaymentPrepareRequest;
import com.lx.governance.order.repo.OutboxRepository;
import com.lx.governance.order.repo.OrderRepository;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderAppService {

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OrderAppService(OrderRepository orderRepository,
                           OutboxRepository outboxRepository,
                           InventoryClient inventoryClient,
                           PaymentClient paymentClient,
                           IdempotencyService idempotencyService) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.idempotencyService = idempotencyService;
    }

    @GlobalTransactional(name = "create-order", rollbackFor = Exception.class)
    public OrderDto create(CreateOrderRequest req, String idempotencyKey) {
        String idemKey = "idem:order:" + (idempotencyKey == null ? "none" : idempotencyKey);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            boolean locked = idempotencyService.tryLock(idemKey, Duration.ofMinutes(5));
            if (!locked) {
                // 幂等：直接返回已存在订单（演示：按 idemKey 找不到就报错）
                throw new IllegalArgumentException("Duplicate request (Idempotency-Key)");
            }
        }

        String orderNo = "O" + System.currentTimeMillis() + (int)(Math.random()*1000);
        orderRepository.insert(orderNo, req.getCustomerId(), req.getProductId(), req.getQuantity(), req.getAmountCents(), "CREATED");

        InventoryDeductRequest deductReq = new InventoryDeductRequest();
        deductReq.setOrderNo(orderNo);
        deductReq.setProductId(req.getProductId());
        deductReq.setQuantity(req.getQuantity());
        ApiResponse<String> inv = inventoryClient.deduct(deductReq);
        if (inv == null || !inv.isSuccess()) {
            throw new IllegalArgumentException("Inventory deduct failed: " + (inv == null ? "null" : inv.getMessage()));
        }

        PaymentPrepareRequest payReq = new PaymentPrepareRequest();
        payReq.setOrderNo(orderNo);
        payReq.setAmountCents(req.getAmountCents());
        ApiResponse<String> pay = paymentClient.prepare(payReq);
        if (pay == null || !pay.isSuccess()) {
            throw new IllegalArgumentException("Payment prepare failed: " + (pay == null ? "null" : pay.getMessage()));
        }

        // Outbox（和订单同库同事务）
        String eventId = UUID.randomUUID().toString();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "orderNo", orderNo,
                    "customerId", req.getCustomerId(),
                    "productId", req.getProductId(),
                    "quantity", req.getQuantity(),
                    "amountCents", req.getAmountCents(),
                    "traceId", Trace.getOrCreate()
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        outboxRepository.insert(eventId, "ORDER", orderNo, "order.created", payload);

        return orderRepository.findByOrderNo(orderNo);
    }

    public OrderDto get(String orderNo) {
        return orderRepository.findByOrderNo(orderNo);
    }

    public void markPaid(String orderNo) {
        orderRepository.updateStatus(orderNo, "PAID");
    }
}
