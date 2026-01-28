package com.example.order.service;

import com.example.order.client.InventoryClient;
import com.example.order.client.PaymentClient;
import com.example.order.dto.PaymentPrepareRequest;
import com.example.order.dto.PaymentPrepareResponse;
import com.example.order.dto.StockDeductRequest;
import com.example.order.dto.StockDeductResponse;
import com.example.order.mapper.OrderMapper;
import com.example.order.mapper.OutboxMapper;
import com.example.order.model.CreateOrderRequest;
import com.example.order.model.OrderEntity;
import com.example.order.model.OutboxEvent;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderService {
    private final OrderMapper orderMapper;
    private final OutboxMapper outboxMapper;
    private final PaymentClient paymentClient;
    private final InventoryClient inventoryClient;

    public OrderService(OrderMapper orderMapper, OutboxMapper outboxMapper, PaymentClient paymentClient, InventoryClient inventoryClient) {
        this.orderMapper = orderMapper;
        this.outboxMapper = outboxMapper;
        this.paymentClient = paymentClient;
        this.inventoryClient = inventoryClient;
    }

    /**
     * 演示：Seata AT 全局事务
     * 1) 创建订单（order_db）
     * 2) 扣减库存（inventory_db）
     * 3) 创建支付单（payment_db）
     * 4) 同事务写 Outbox（order_db）
     */
    @GlobalTransactional(name = "gtx-create-order", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public String createOrder(CreateOrderRequest req) {
        String orderNo = "O" + UUID.randomUUID().toString().replace("-", "");

        OrderEntity o = new OrderEntity();
        o.setOrderNo(orderNo);
        o.setCustomerId(req.getCustomerId());
        o.setProductId(req.getProductId());
        o.setQuantity(req.getQuantity());
        o.setAmountCents(req.getAmountCents());
        o.setStatus("NEW");
        orderMapper.insert(o);

        StockDeductRequest sreq = new StockDeductRequest();
        sreq.setOrderNo(orderNo);
        sreq.setProductId(req.getProductId());
        sreq.setQuantity(req.getQuantity());
        StockDeductResponse sresp = inventoryClient.deduct(sreq);
        if (!sresp.isSuccess()) {
            throw new IllegalStateException("inventory deduct failed: " + sresp.getCode());
        }

        PaymentPrepareRequest preq = new PaymentPrepareRequest();
        preq.setOrderNo(orderNo);
        preq.setCustomerId(req.getCustomerId());
        preq.setAmountCents(req.getAmountCents());
        PaymentPrepareResponse presp = paymentClient.prepare(preq);
        if (!presp.isSuccess()) {
            throw new IllegalStateException("payment prepare failed: " + presp.getCode());
        }

        // outbox event: OrderCreated
        OutboxEvent e = new OutboxEvent();
        e.setAggregateType("Order");
        e.setAggregateId(orderNo);
        e.setEventType("OrderCreated");
        e.setPayloadJson(String.format("{\"orderNo\":\"%s\",\"customerId\":\"%s\",\"amountCents\":%d}", orderNo, req.getCustomerId(), req.getAmountCents()));
        e.setStatus("NEW");
        outboxMapper.insert(e);

        return orderNo;
    }

    public OrderEntity get(String orderNo) {
        return orderMapper.findByOrderNo(orderNo);
    }
}
