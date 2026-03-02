package com.lx.governance.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.client.InventoryClient;
import com.lx.governance.order.domain.InventoryDeductRequest;
import com.lx.governance.order.id.OrderNoGenerator;
import com.lx.governance.order.repo.OutboxRepository;
import com.lx.governance.order.repo.OrderRepository;
import com.lx.governance.order.repo.SeckillOrderGuardRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class SeckillOrderStreamConsumer {

    private static final String STREAM_KEY = "seckill:stream:orders";
    private static final String GROUP = "g1";
    private static final String CONSUMER = "c1";
    private static final int BATCH_SIZE = 100;

    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate txTemplate;
    private final InventoryClient inventoryClient;
    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final SeckillOrderGuardRepository seckillOrderGuardRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean groupReady = false;

    public SeckillOrderStreamConsumer(StringRedisTemplate redisTemplate,
                                      TransactionTemplate txTemplate,
                                      InventoryClient inventoryClient,
                                      OrderRepository orderRepository,
                                      OutboxRepository outboxRepository,
                                      SeckillOrderGuardRepository seckillOrderGuardRepository,
                                      OrderNoGenerator orderNoGenerator) {
        this.redisTemplate = redisTemplate;
        this.txTemplate = txTemplate;
        this.inventoryClient = inventoryClient;
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.seckillOrderGuardRepository = seckillOrderGuardRepository;
        this.orderNoGenerator = orderNoGenerator;
    }

    @Scheduled(fixedDelay = 50)
    public void consume() {
        ensureGroup();
        if (!groupReady) {
            return;
        }

        List<MapRecord<String, Object, Object>> msgs = redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(BATCH_SIZE).block(Duration.ofMillis(500)),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );
        if (msgs == null || msgs.isEmpty()) {
            return;
        }

        for (MapRecord<String, Object, Object> m : msgs) {
            try {
                txTemplate.execute(status -> {
                    String reqId = stringField(m, "reqId");
                    String userId = stringField(m, "userId");
                    String skuId = stringField(m, "skuId");
                    if (reqId == null || userId == null || skuId == null) {
                        throw new IllegalArgumentException("invalid stream payload");
                    }
                    String orderNo = orderNoGenerator.nextSeckillOrderNo();

                    // 幂等：uk_user_sku，重复消息/重复点击只会成功一次
                    seckillOrderGuardRepository.insert(userId, skuId, reqId);

                    deductStockByInventoryService(orderNo, skuId, 1);
                    orderRepository.insert(orderNo, userId, skuId, 1, 0, "UNPAID");

                    String eventId = UUID.randomUUID().toString();
                    String payload;
                    try {
                        payload = objectMapper.writeValueAsString(Map.of(
                                "orderNo", orderNo,
                                "customerId", userId,
                                "productId", skuId,
                                "quantity", 1,
                                "amountCents", 0,
                                "traceId", Trace.getOrCreate(),
                                "reqId", reqId
                        ));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    outboxRepository.insert(eventId, "ORDER", orderNo, "order.created.v2", payload);
                    return null;
                });
                redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, m.getId());
            } catch (DuplicateKeyException e) {
                redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, m.getId());
            } catch (Exception ignored) {
                // 不 ack：消息留在 pending，后续可做重试/claim + 告警/死信
            }
        }
    }

    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.latest(), GROUP);
            groupReady = true;
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                groupReady = true;
                return;
            }
            if (message != null && message.contains("requires the key to exist")) {
                return;
            }
            throw e;
        }
    }

    private void deductStockByInventoryService(String orderNo, String skuId, int qty) {
        InventoryDeductRequest req = new InventoryDeductRequest();
        req.setOrderNo(orderNo);
        req.setProductId(skuId);
        req.setQuantity(qty);
        ApiResponse<String> resp = inventoryClient.deduct(req);
        if (resp == null || !resp.isSuccess()) {
            throw new IllegalArgumentException("inventory deduct failed: " + (resp == null ? "null" : resp.getMessage()));
        }
    }

    private String stringField(MapRecord<String, Object, Object> record, String key) {
        Object value = record.getValue().get(key);
        return value == null ? null : String.valueOf(value);
    }
}
