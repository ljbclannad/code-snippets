package com.lx.governance.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.client.InventoryClient;
import com.lx.governance.order.client.PaymentClient;
import com.lx.governance.order.domain.CreateOrderRequest;
import com.lx.governance.order.domain.InventoryDeductRequest;
import com.lx.governance.order.domain.OrderDto;
import com.lx.governance.order.domain.PaymentDto;
import com.lx.governance.order.domain.PaymentPrepareRequest;
import com.lx.governance.order.context.UserReadWriteContext;
import com.lx.governance.order.helper.ReadMasterHelper;
import com.lx.governance.order.id.OrderNoGenerator;
import com.lx.governance.order.repo.OutboxRepository;
import com.lx.governance.order.repo.OrderRepository;
import com.lx.governance.order.repo.PaymentEventConsumeRepository;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderAppService {
    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final PaymentEventConsumeRepository paymentEventConsumeRepository;
    private final IdempotencyService idempotencyService;
    private final StringRedisTemplate redisTemplate;
    private final OrderNoGenerator orderNoGenerator;
    private final UserReadWriteContext readWriteContext;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String STREAM_KEY = "seckill:stream:orders";
    private static final String CREATE_V2_LUA = """
            local stock = tonumber(redis.call('GET', KEYS[1]) or "0")
            if stock <= 0 then
              return {0, "SOLD_OUT"}
            end

            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
              return {0, "DUP_BUY"}
            end

            redis.call('DECR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])

            redis.call('XADD', KEYS[3], '*',
              'reqId', ARGV[2],
              'userId', ARGV[1],
              'skuId', ARGV[3]
            )

            return {1, "OK"}
            """;

    public OrderAppService(OrderRepository orderRepository,
                           OutboxRepository outboxRepository,
                           InventoryClient inventoryClient,
                           PaymentClient paymentClient,
                           PaymentEventConsumeRepository paymentEventConsumeRepository,
                           IdempotencyService idempotencyService,
                           StringRedisTemplate redisTemplate,
                           OrderNoGenerator orderNoGenerator,
                           UserReadWriteContext readWriteContext) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.paymentEventConsumeRepository = paymentEventConsumeRepository;
        this.idempotencyService = idempotencyService;
        this.redisTemplate = redisTemplate;
        this.orderNoGenerator = orderNoGenerator;
        this.readWriteContext = readWriteContext;
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

        String orderNo = orderNoGenerator.nextOrderNo();
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
        payReq.setUserId(parseUserId(req.getCustomerId()));
        payReq.setAmountCents(req.getAmountCents());
        ApiResponse<PaymentDto> pay = paymentClient.prepare(payReq);
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

        // 标记用户发生了写操作，3秒内该用户的查询将读主库
        readWriteContext.markUserWrite(parseUserId(req.getCustomerId()));

        return orderRepository.findByOrderNo(orderNo);
    }

    public OrderDto get(String orderNo) {
        return orderRepository.findByOrderNo(orderNo);
    }

    /**
     * 获取订单（支持读写分离优化）
     * @param orderNo 订单号
     * @param userId 用户ID（用于判断是否需要读主库）
     * @return 订单信息
     */
    public OrderDto get(String orderNo, Long userId) {
        if (userId != null && readWriteContext.shouldReadMaster(userId)) {
            // 3秒内，强制读主库
            return ReadMasterHelper.readFromMaster(() -> orderRepository.findByOrderNo(orderNo));
        } else {
            // 正常读从库
            return orderRepository.findByOrderNo(orderNo);
        }
    }

    public void markPaid(String orderNo) {
        orderRepository.updateStatus(orderNo, "PAID");
    }

    @Transactional
    public boolean markPaidFromEvent(String eventId, String eventType, String orderNo) {
        boolean firstConsume = paymentEventConsumeRepository.tryMarkConsumed(eventId, eventType, orderNo);
        if (!firstConsume) {
            return false;
        }
        orderRepository.updateStatus(orderNo, "PAID");
        return true;
    }

    public Map<String, String> paymentCallbackPass(String orderNo) {
        OrderDto order = orderRepository.findByOrderNo(orderNo);
        if (order == null) {
            throw new IllegalArgumentException("order not found: " + orderNo);
        }

        String eventId = UUID.randomUUID().toString();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "eventId", eventId,
                    "orderNo", orderNo,
                    "result", "PASS",
                    "traceId", Trace.getOrCreate()
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 统一走 outbox-consumer 可靠投递，order-service 仅落库事件
        outboxRepository.insert(eventId, "ORDER", orderNo, "payment.callback.pass", payload);

        return Map.of(
                "orderNo", orderNo,
                "eventId", eventId,
                "queued", "true"
        );
    }

    public Map<String, String> createV2(CreateOrderRequest req, String requestId) {
        String userId = req.getCustomerId();
        String skuId = req.getProductId();
        String reqId = (requestId == null || requestId.isBlank()) ? UUID.randomUUID().toString() : requestId;

        String stockKey = "seckill:stock:sku:" + skuId;
        String buyersKey = "seckill:buyers:sku:" + skuId;

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(CREATE_V2_LUA);
        script.setResultType(List.class);

        List<?> result = redisTemplate.execute(
                script,
                List.of(stockKey, buyersKey, STREAM_KEY),
                userId, reqId, skuId
        );
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("Seckill script execute failed");
        }

        String status = String.valueOf(result.get(0));
        String code = String.valueOf(result.get(1));
        return Map.of(
                "reqId", reqId,
                "status", status,
                "code", code
        );
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
