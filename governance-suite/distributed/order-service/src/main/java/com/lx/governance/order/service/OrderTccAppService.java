package com.lx.governance.order.service;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.monitoring.annotation.Monitored;
import com.lx.governance.order.client.InventoryClient;
import com.lx.governance.order.client.PaymentClient;
import com.lx.governance.order.context.UserReadWriteContext;
import com.lx.governance.order.domain.CreateOrderRequest;
import com.lx.governance.order.domain.InventoryDeductRequest;
import com.lx.governance.order.domain.OrderDto;
import com.lx.governance.order.domain.PaymentPrepareRequest;
import com.lx.governance.order.id.OrderNoGenerator;
import com.lx.governance.order.repo.OrderRepository;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class OrderTccAppService {

    private final OrderRepository orderRepository;
    private final OrderTccAction orderTccAction;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final IdempotencyService idempotencyService;
    private final OrderNoGenerator orderNoGenerator;
    private final UserReadWriteContext readWriteContext;

    public OrderTccAppService(OrderRepository orderRepository,
                              OrderTccAction orderTccAction,
                              InventoryClient inventoryClient,
                              PaymentClient paymentClient,
                              IdempotencyService idempotencyService,
                              OrderNoGenerator orderNoGenerator,
                              UserReadWriteContext readWriteContext) {
        this.orderRepository = orderRepository;
        this.orderTccAction = orderTccAction;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.idempotencyService = idempotencyService;
        this.orderNoGenerator = orderNoGenerator;
        this.readWriteContext = readWriteContext;
    }

    @Monitored(value = "order.create.tcc", description = "TCC订单创建")
    @GlobalTransactional(name = "create-order-tcc", rollbackFor = Exception.class)
    public OrderDto create(CreateOrderRequest req, String idempotencyKey) {
        String idemKey = "idem:order:tcc:" + (idempotencyKey == null ? "none" : idempotencyKey);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            boolean locked = idempotencyService.tryLock(idemKey, Duration.ofMinutes(5));
            if (!locked) {
                throw new IllegalArgumentException("Duplicate request (Idempotency-Key)");
            }
        }

        String orderNo = orderNoGenerator.nextOrderNo();
        orderTccAction.prepare(null, orderNo, req.getCustomerId(), req.getProductId(), req.getQuantity(), req.getAmountCents());

        InventoryDeductRequest deductRequest = new InventoryDeductRequest();
        deductRequest.setOrderNo(orderNo);
        deductRequest.setProductId(req.getProductId());
        deductRequest.setQuantity(req.getQuantity());
        ApiResponse<String> inventoryResult = inventoryClient.tryDeductTcc(deductRequest);
        if (inventoryResult == null || !inventoryResult.isSuccess()) {
            throw new IllegalArgumentException("Inventory TCC try failed: " + (inventoryResult == null ? "null" : inventoryResult.getMessage()));
        }

        PaymentPrepareRequest paymentRequest = new PaymentPrepareRequest();
        paymentRequest.setOrderNo(orderNo);
        paymentRequest.setUserId(parseUserId(req.getCustomerId()));
        paymentRequest.setAmountCents(req.getAmountCents());
        ApiResponse<String> paymentResult = paymentClient.tryPrepareTcc(paymentRequest);
        if (paymentResult == null || !paymentResult.isSuccess()) {
            throw new IllegalArgumentException("Payment TCC try failed: " + (paymentResult == null ? "null" : paymentResult.getMessage()));
        }

        readWriteContext.markUserWrite(parseUserId(req.getCustomerId()));
        return orderRepository.findByOrderNo(orderNo);
    }

    private Long parseUserId(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("missing customerId/userId");
        }
        try {
            return Long.parseLong(customerId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("customerId must be numeric userId");
        }
    }
}
