# 可观测性增强实施计划 - Part 4: 业务指标埋点

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在各服务的关键业务方法添加监控埋点，实现业务指标采集

**Architecture:** 使用 @Monitored 注解进行 AOP 自动埋点，特殊场景（Seata、秒杀、Outbox）使用手动埋点

**Tech Stack:**
- @Monitored 注解（已实现）
- Micrometer MeterRegistry
- Micrometer Tracer

---

## Task 1: User Service 业务指标埋点

**Files:**
- Modify: `user-service/src/main/java/com/lx/governance/user/service/UserService.java`
- Modify: `user-service/src/main/java/com/lx/governance/user/service/AuthTokenService.java`

**目标指标**:
- `user.register.total`: 注册次数（Counter，status: success/failure）
- `user.login.total`: 登录次数（Counter，status: success/failure）
- `user.token.issued`: Token 签发次数（Counter）

**步骤**:

1. 在 `UserService.register()` 方法添加 @Monitored 注解：
```java
@Monitored(value = "user.register", description = "用户注册")
public User register(RegisterRequest request) {
    // 现有业务逻辑
}
```

2. 在 `UserService.login()` 方法添加 @Monitored 注解：
```java
@Monitored(value = "user.login", description = "用户登录")
public LoginResponse login(LoginRequest request) {
    // 现有业务逻辑
}
```

3. 在 `AuthTokenService.generateToken()` 方法添加手动计数：
```java
@Autowired
private MeterRegistry meterRegistry;

public String generateToken(Long userId, String username) {
    // 现有逻辑
    String token = ...;
    
    // 记录 token 签发
    meterRegistry.counter("user.token.issued").increment();
    
    return token;
}
```

4. 编译验证：`mvn compile -pl user-service`

5. 提交：
```bash
git add user-service/src/main/java/com/lx/governance/user/service/
git commit -m "feat(user-service): add business metrics monitoring

- Add @Monitored to register and login methods
- Track token issuance count
- Enable user behavior monitoring"
```

---

## Task 2: Order Service 业务指标埋点

**Files:**
- Modify: `order-service/src/main/java/com/lx/governance/order/service/OrderAppService.java`
- Modify: `order-service/src/main/java/com/lx/governance/order/service/SeckillService.java` (如果存在)

**目标指标**:
- `order.create.total`: 订单创建次数（通过 @Monitored 自动）
- `order.create.duration`: 订单创建耗时（通过 @Monitored 自动）
- `seata.transaction.total`: Seata 事务次数（Counter，status: commit/rollback）
- `seata.transaction.duration`: Seata 事务耗时（Timer）
- `seckill.stock.remaining`: 秒杀库存剩余（Gauge）
- `seckill.orders.total`: 秒杀订单数（Counter）

**步骤**:

1. 在 `OrderAppService.createOrder()` 添加 @Monitored 和 Seata 追踪：

```java
@Autowired
private MeterRegistry meterRegistry;

@Autowired
private Tracer tracer;

@Monitored(value = "order.create", description = "订单创建")
@GlobalTransactional
public Order createOrder(CreateOrderRequest request) {
    // 创建 Seata 事务 span
    Span span = tracer.nextSpan().name("seata-global-transaction");
    Timer.Sample sample = Timer.start(meterRegistry);
    
    try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
        String xid = RootContext.getXID();
        span.tag("seata.xid", xid);
        span.tag("order.no", request.getOrderNo());
        span.tag("user.id", String.valueOf(request.getUserId()));
        
        // 现有业务逻辑
        Order order = doCreateOrder(request);
        
        // 记录 Seata 事务成功
        sample.stop(Timer.builder("seata.transaction.duration")
                .tag("status", "commit")
                .register(meterRegistry));
        
        meterRegistry.counter("seata.transaction.total", "status", "commit").increment();
        span.tag("seata.status", "commit");
        
        return order;
        
    } catch (Exception ex) {
        // 记录 Seata 事务回滚
        sample.stop(Timer.builder("seata.transaction.duration")
                .tag("status", "rollback")
                .register(meterRegistry));
        
        meterRegistry.counter("seata.transaction.total", "status", "rollback").increment();
        span.tag("seata.status", "rollback");
        span.tag("error", "true");
        
        throw ex;
    } finally {
        span.end();
    }
}
```

2. 如果有秒杀服务，添加库存监控：

```java
public void updateSeckillStock(String skuId, int remaining) {
    meterRegistry.gauge("seckill.stock.remaining", 
            Tags.of("sku_id", skuId), 
            remaining);
}

public boolean executeSeckill(String skuId, Long userId) {
    Span span = tracer.nextSpan().name("seckill-lua-script");
    
    try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
        span.tag("sku.id", skuId);
        span.tag("user.id", String.valueOf(userId));
        
        Long result = redisTemplate.execute(seckillScript, 
                Collections.singletonList(skuId), 
                userId);
        
        boolean success = result == 1;
        span.tag("result", success ? "success" : "failure");
        
        meterRegistry.counter("seckill.orders.total", 
                "sku_id", skuId, 
                "status", success ? "success" : "failure").increment();
        
        return success;
        
    } finally {
        span.end();
    }
}
```

3. 编译验证：`mvn compile -pl order-service`

4. 提交：
```bash
git add order-service/src/main/java/com/lx/governance/order/service/
git commit -m "feat(order-service): add business metrics and Seata tracing

- Add @Monitored to createOrder method
- Track Seata transaction commit/rollback with Timer and Counter
- Add manual span for Seata global transaction
- Track seckill stock and order count
- Add manual span for seckill Lua script execution"
```

---

## Task 3: Payment Service 业务指标埋点

**Files:**
- Modify: `payment-service/src/main/java/com/lx/governance/payment/service/PaymentService.java`

**目标指标**:
- `payment.prepare.total`: 支付预处理次数（通过 @Monitored 自动）
- `payment.confirm.total`: 支付确认次数（通过 @Monitored 自动）

**步骤**:

1. 在 `PaymentService.prepare()` 添加 @Monitored：
```java
@Monitored(value = "payment.prepare", description = "支付预处理")
public void prepare(PreparePaymentRequest request) {
    // 现有业务逻辑
}
```

2. 在 `PaymentService.confirm()` 添加 @Monitored：
```java
@Monitored(value = "payment.confirm", description = "支付确认")
public void confirm(ConfirmPaymentRequest request) {
    // 现有业务逻辑
}
```

3. 编译验证：`mvn compile -pl payment-service`

4. 提交：
```bash
git add payment-service/src/main/java/com/lx/governance/payment/service/
git commit -m "feat(payment-service): add business metrics monitoring

- Add @Monitored to prepare and confirm methods
- Track payment success/failure rates automatically"
```

---

## Task 4: Inventory Service 业务指标埋点

**Files:**
- Modify: `inventory-service/src/main/java/com/lx/governance/inventory/service/InventoryService.java`

**目标指标**:
- `inventory.deduct.total`: 库存扣减次数（通过 @Monitored 自动）
- `inventory.deduct.retry`: 乐观锁重试次数（Counter）

**步骤**:

1. 在 `InventoryService.deduct()` 添加 @Monitored：
```java
@Monitored(value = "inventory.deduct", description = "库存扣减")
public void deduct(DeductInventoryRequest request) {
    // 现有业务逻辑
}
```

2. 在乐观锁重试循环中添加计数：
```java
@Autowired
private MeterRegistry meterRegistry;

public void deduct(DeductInventoryRequest request) {
    int maxRetries = 5;
    for (int i = 0; i < maxRetries; i++) {
        try {
            // 尝试扣减库存
            int updated = inventoryMapper.deductWithOptimisticLock(...);
            if (updated > 0) {
                return; // 成功
            }
        } catch (Exception ex) {
            // 记录重试
            if (i < maxRetries - 1) {
                meterRegistry.counter("inventory.deduct.retry").increment();
            }
        }
    }
    throw new InsufficientStockException();
}
```

3. 编译验证：`mvn compile -pl inventory-service`

4. 提交：
```bash
git add inventory-service/src/main/java/com/lx/governance/inventory/service/
git commit -m "feat(inventory-service): add business metrics monitoring

- Add @Monitored to deduct method
- Track optimistic lock retry count
- Enable inventory operation monitoring"
```

---

## Task 5: Outbox Consumer 业务指标埋点

**Files:**
- Modify: `outbox-consumer/src/main/java/com/lx/governance/outbox/service/OutboxPollingService.java`

**目标指标**:
- `outbox.poll.total`: 轮询次数（Counter）
- `outbox.publish.total`: 发布次数（Counter，status: success/failure）
- `outbox.publish.lag`: 发布延迟（Gauge，秒）
- `outbox.dead.total`: DEAD 消息数（Counter）
- `outbox.retry.total`: 重试次数（Counter）

**步骤**:

1. 在轮询方法中添加指标埋点：

```java
@Autowired
private MeterRegistry meterRegistry;

@Autowired
private Tracer tracer;

@Scheduled(fixedDelay = 1000)
public void pollAndPublish() {
    meterRegistry.counter("outbox.poll.total").increment();
    
    List<OutboxMessage> messages = outboxRepository.findPendingMessages();
    
    for (OutboxMessage msg : messages) {
        publishMessage(msg);
    }
}

private void publishMessage(OutboxMessage msg) {
    Span span = tracer.nextSpan().name("outbox-publish");
    
    try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
        span.tag("message.id", msg.getId());
        span.tag("exchange", msg.getExchange());
        span.tag("routing.key", msg.getRoutingKey());
        
        // 计算发布延迟
        long lagSeconds = Duration.between(msg.getCreatedAt(), Instant.now()).getSeconds();
        meterRegistry.gauge("outbox.publish.lag", lagSeconds);
        span.tag("publish.lag.seconds", String.valueOf(lagSeconds));
        
        // 发布消息
        rabbitTemplate.send(msg.getExchange(), msg.getRoutingKey(), msg.getPayload());
        
        // 记录成功
        meterRegistry.counter("outbox.publish.total", "status", "success").increment();
        span.tag("status", "success");
        
        // 更新状态为 SENT
        outboxRepository.updateStatus(msg.getId(), "SENT");
        
    } catch (Exception ex) {
        // 记录失败
        meterRegistry.counter("outbox.publish.total", "status", "failure").increment();
        meterRegistry.counter("outbox.retry.total").increment();
        span.tag("status", "failure");
        span.tag("error", "true");
        
        // 更新重试次数
        int retryCount = msg.getRetryCount() + 1;
        if (retryCount >= 5) {
            // 标记为 DEAD
            outboxRepository.updateStatus(msg.getId(), "DEAD");
            meterRegistry.counter("outbox.dead.total").increment();
        } else {
            outboxRepository.incrementRetry(msg.getId());
        }
        
    } finally {
        span.end();
    }
}
```

2. 编译验证：`mvn compile -pl outbox-consumer`

3. 提交：
```bash
git add outbox-consumer/src/main/java/com/lx/governance/outbox/service/
git commit -m "feat(outbox-consumer): add business metrics and tracing

- Track polling count
- Track publish success/failure with Counter
- Measure publish lag with Gauge
- Track DEAD message count
- Track retry count
- Add manual span for each message publish"
```

---

## Task 6: 端到端验证

**Files:**
- None (testing only)

**步骤**:

1. 构建所有服务：`mvn clean package -DskipTests -T 1C`

2. 启动基础设施：`docker compose up -d`

3. 启动所有服务（每个在独立终端）：
```bash
mvn -q -pl gateway spring-boot:run
mvn -q -pl user-service spring-boot:run
mvn -q -pl order-service spring-boot:run
mvn -q -pl payment-service spring-boot:run
mvn -q -pl inventory-service spring-boot:run
mvn -q -pl outbox-consumer spring-boot:run
```

4. 执行业务操作触发指标：

```bash
# 注册用户
curl -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456"}'

# 登录获取 token
TOKEN=$(curl -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456"}' | jq -r '.data.token')

# 创建订单
curl -X POST http://localhost:8080/api/order/create \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuId":"SKU001","quantity":1}'
```

5. 验证 Prometheus 指标：

访问各服务的 /actuator/prometheus 端点，检查自定义指标：

```bash
# User Service
curl http://localhost:8084/actuator/prometheus | grep -E "user_register|user_login|user_token"

# Order Service
curl http://localhost:8081/actuator/prometheus | grep -E "order_create|seata_transaction|seckill"

# Payment Service
curl http://localhost:8082/actuator/prometheus | grep -E "payment_prepare|payment_confirm"

# Inventory Service
curl http://localhost:8083/actuator/prometheus | grep -E "inventory_deduct"

# Outbox Consumer
curl http://localhost:8090/actuator/prometheus | grep -E "outbox_poll|outbox_publish"
```

6. 验证 Jaeger 追踪：

访问 http://localhost:16686，搜索 trace：
- 选择 Service: gateway
- 点击 Find Traces
- 验证调用链：Gateway → Order → Inventory/Payment
- 验证 Seata 事务 span 存在
- 验证 span tags 包含业务信息（order.no, user.id, seata.xid）

7. 验证 Prometheus 查询：

访问 http://localhost:9090，执行查询：
```promql
# 订单创建成功率
sum(rate(order_create_total{status="success"}[5m])) / sum(rate(order_create_total[5m])) * 100

# Seata 事务回滚率
sum(rate(seata_transaction_total{status="rollback"}[5m])) / sum(rate(seata_transaction_total[5m])) * 100

# 支付成功率
sum(rate(payment_confirm_total{status="success"}[5m])) / sum(rate(payment_confirm_total[5m])) * 100
```

8. 提交验证总结：
```bash
git commit --allow-empty -m "test: verify business metrics and distributed tracing

Verified:
- All services expose custom business metrics
- @Monitored annotation working correctly
- Manual metrics (Seata, seckill, outbox) collecting data
- Distributed tracing spans created with correct tags
- Prometheus can query all custom metrics
- Jaeger shows complete trace with business context

Business metrics verified:
- user.register.total, user.login.total, user.token.issued
- order.create.total, seata.transaction.total, seckill.stock.remaining
- payment.prepare.total, payment.confirm.total
- inventory.deduct.total, inventory.deduct.retry
- outbox.poll.total, outbox.publish.total, outbox.publish.lag"
```

---

## 完成标准

Part 4 完成后，应满足：

✅ 所有 5 个服务的关键业务方法添加了监控
✅ @Monitored 注解在生产代码中使用
✅ 手动埋点（Seata、秒杀、Outbox）已实现
✅ 所有自定义指标在 Prometheus 中可查询
✅ Jaeger 显示完整的分布式调用链
✅ Span 包含业务上下文标签
✅ 端到端验证通过

**下一步**: 创建 Grafana 仪表板（可选，或作为独立任务）
