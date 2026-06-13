# 分布式系统治理开发规范

本文档定义分布式系统治理服务（distributed/ 目录）的开发规范和工作规则。

## 模块范围

分布式系统治理包含以下模块：

- `gateway` - API 网关
- `user-service` - 用户服务
- `order-service` - 订单服务（Seata TM）
- `payment-service` - 支付服务（Seata RM）
- `inventory-service` - 库存服务（Seata RM）
- `outbox-consumer` - Outbox 消费者
- `appointment-service` - 预约服务

## 硬性规则

### 模块隔离

- **必须**在修改代码前识别目标模块
- **必须**将构建、测试、验证限定在相关模块内
- **不得**因模块相邻而修改无关文件
- **不得**在分布式系统内进行跨模块重构，除非用户明确要求

### 依赖管理

- **必须**验证共享依赖（`common` 模块）的修改不会破坏其他服务
- **不得**在分布式服务的 pom.xml 中引入 AI 相关依赖（Spring AI、Milvus）
- **不得**修改 Seata、ShardingSphere 版本，除非用户明确要求

### 构建与验证

- **必须**使用模块级 Maven 命令验证更改：
  ```bash
  mvn -f distributed/<module>/pom.xml compile
  mvn -f distributed/<module>/pom.xml test
  ```
- **必须**在声称"验证通过"前提供实际证据（编译输出、测试结果、日志）
- **不得**在未验证的情况下声称代码可以运行

### 分布式事务规则

- **必须**在 TM（事务管理器）方法上添加 `@GlobalTransactional` 注解
- **必须**在 RM（资源管理器）的事务方法上使用 `@Transactional`
- **必须**正确配置 `rollbackFor` 属性
- **不得**在全局事务中调用外部非事务性 API（如第三方支付）

## 服务职责边界

### gateway

**职责：**
- 统一入口和路由
- 用户认证和令牌验证
- 全局限流和熔断
- 跨域处理

**不负责：**
- 业务逻辑处理
- 数据存储

**修改指导：**
- 修改路由规则时，必须更新 API 文档
- 修改限流规则时，必须提供压测数据支持
- 添加新的过滤器时，必须考虑性能影响

### user-service

**职责：**
- 用户注册和登录
- 令牌生成和验证
- 用户信息管理

**不负责：**
- 订单、支付等业务逻辑
- 分布式事务管理（不是 TM）

**修改指导：**
- 修改令牌生成逻辑时，必须保持向后兼容
- 修改密码加密算法时，必须提供迁移方案
- 添加新字段时，必须提供数据库迁移脚本

### order-service

**职责：**
- 订单创建和管理
- **Seata TM（事务管理器）** - 发起全局事务
- 幂等性控制

**不负责：**
- 直接操作支付和库存数据库
- 消息的最终发送（由 outbox-consumer 负责）

**修改指导：**
- 修改订单创建流程时，必须确保 Seata 全局事务正确开启
- 添加新的远程调用时，必须考虑分支事务注册
- 修改幂等键逻辑时，必须更新 API 文档

### payment-service

**职责：**
- 支付预扣和确认
- **Seata RM（资源管理器）** - 参与全局事务

**不负责：**
- 订单创建逻辑
- 发起全局事务（由 order-service 发起）

**修改指导：**
- 修改支付状态机时，必须考虑回滚场景
- 添加新的支付方式时，必须实现两阶段提交
- 修改数据库表结构时，必须保留 `undo_log` 表

### inventory-service

**职责：**
- 库存扣减和释放
- **Seata RM（资源管理器）** - 参与全局事务

**不负责：**
- 订单创建逻辑
- 发起全局事务

**修改指导：**
- 修改扣减逻辑时，必须考虑回滚场景
- 添加库存预警时，不要影响主流程性能
- 修改数据库表结构时，必须保留 `undo_log` 表

### outbox-consumer

**职责：**
- 轮询 Outbox 表
- 发送消息到 RabbitMQ
- 标记消息状态

**不负责：**
- 业务逻辑处理
- 直接调用其他服务

**修改指导：**
- 修改轮询间隔时，必须考虑数据库压力
- 修改重试逻辑时，必须防止消息重复发送
- 添加新的事件类型时，必须更新消费者

### appointment-service

**职责：**
- 预约创建和管理
- Redis Lua 原子化库存扣减
- 热门科室缓存

**不负责：**
- 用户认证（由 gateway 负责）
- 分布式事务（使用 Redis 原子操作）

**修改指导：**
- 修改 Lua 脚本时，必须确保原子性
- 修改缓存策略时，必须考虑缓存一致性
- 添加新的预约类型时，必须更新 Redis 键命名规范

## 分布式事务开发规范

### Seata AT 模式最佳实践

#### 1. TM（事务管理器）开发

```java
@Service
public class OrderService {
    
    @GlobalTransactional(
        name = "create-order",           // 全局事务名称
        rollbackFor = Exception.class    // 回滚条件
    )
    public void createOrder(OrderRequest req) {
        // 1. 本地业务逻辑
        orderMapper.insert(order);
        
        // 2. 调用远程服务（自动注册分支事务）
        paymentClient.prepare(req.getAmountCents());
        inventoryClient.deduct(req.getProductId(), req.getQuantity());
        
        // 3. 写入 Outbox（同一事务）
        outboxMapper.insert(outboxMessage);
    }
}
```

#### 2. RM（资源管理器）开发

```java
@Service
public class PaymentService {
    
    @Transactional(rollbackFor = Exception.class)
    public void prepare(Long amountCents) {
        // 本地业务逻辑
        paymentMapper.insert(payment);
        
        // Seata 自动拦截，生成 undo_log
    }
}
```

#### 3. 常见陷阱

**❌ 错误：在全局事务中调用非事务性 API**

```java
@GlobalTransactional
public void createOrder() {
    orderMapper.insert(order);
    
    // 错误：第三方支付 API 不受 Seata 管理
    alipayClient.pay(order.getAmount());
}
```

**✅ 正确：使用两阶段提交**

```java
@GlobalTransactional
public void createOrder() {
    orderMapper.insert(order);
    
    // 第一阶段：预扣
    paymentService.prepare(order.getAmount());
}

// 全局事务提交后，异步确认
@Async
public void confirmPayment(Long orderId) {
    alipayClient.pay(order.getAmount());
}
```

### Outbox 模式开发规范

#### 1. 写入 Outbox

```java
@Transactional
public void createOrder(OrderRequest req) {
    // 业务操作
    orderMapper.insert(order);
    
    // 写入 Outbox（同一事务）
    OutboxMessage msg = new OutboxMessage();
    msg.setEventType("ORDER_CREATED");
    msg.setPayload(JsonUtils.toJson(order));
    msg.setStatus(OutboxStatus.PENDING);
    outboxMapper.insert(msg);
}
```

#### 2. Outbox Consumer 轮询

```java
@Scheduled(fixedDelay = 1000)
public void pollAndSend() {
    List<OutboxMessage> pending = outboxMapper.selectPending();
    
    for (OutboxMessage msg : pending) {
        try {
            rabbitTemplate.send(msg.getEventType(), msg.getPayload());
            outboxMapper.markSent(msg.getId());
        } catch (Exception e) {
            log.error("Failed to send message: {}", msg.getId(), e);
            outboxMapper.incrementRetry(msg.getId());
        }
    }
}
```

#### 3. 注意事项

- Outbox 记录必须与业务操作在同一事务
- 标记已发送必须在消息确认发送后
- 必须实现重试和失败处理
- 定期清理已发送的历史记录

## 数据库规范

### 表设计规范

#### 1. 必须字段

```sql
CREATE TABLE example (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,  -- 乐观锁
    deleted TINYINT NOT NULL DEFAULT 0  -- 逻辑删除
);
```

#### 2. Seata undo_log 表

每个参与 Seata 事务的数据库必须创建 `undo_log` 表：

```sql
CREATE TABLE undo_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,
    xid VARCHAR(100) NOT NULL,
    context VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB NOT NULL,
    log_status INT NOT NULL,
    log_created DATETIME NOT NULL,
    log_modified DATETIME NOT NULL,
    INDEX idx_xid (xid),
    INDEX idx_branch_id (branch_id)
);
```

#### 3. Outbox 表

```sql
CREATE TABLE outbox_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,  -- PENDING, SENT, FAILED
    retry_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    sent_at DATETIME,
    INDEX idx_status_created (status, created_at)
);
```

### 索引规范

- 主键必须使用 `BIGINT AUTO_INCREMENT`
- 外键字段必须建索引
- 查询条件字段必须建索引
- 联合索引遵循最左前缀原则

### 读写分离规范

使用 ShardingSphere 配置读写分离：

```yaml
spring:
  shardingsphere:
    rules:
      readwrite-splitting:
        data-sources:
          ds:
            write-data-source-name: master
            read-data-source-names: slave
            load-balancer-name: round-robin
```

**注意事项：**
- 写操作后立即读，可能读到旧数据（主从延迟）
- 事务内的读操作会自动路由到主库
- 强制读主库：使用 `HintManager`

## 限流与熔断

### Sentinel 规则配置

#### 1. QPS 限流

```java
FlowRule rule = new FlowRule();
rule.setResource("GET:/api/order/list");
rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
rule.setCount(100);  // 100 QPS
FlowRuleManager.loadRules(Collections.singletonList(rule));
```

#### 2. 熔断降级

```java
DegradeRule rule = new DegradeRule();
rule.setResource("payment-service");
rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
rule.setCount(0.5);  // 异常率超过 50%
rule.setTimeWindow(10);  // 熔断 10 秒
DegradeRuleManager.loadRules(Collections.singletonList(rule));
```

### 降级处理

```java
@SentinelResource(
    value = "payment-service",
    fallback = "paymentFallback"
)
public PaymentResult callPayment() {
    return paymentClient.prepare(100L);
}

public PaymentResult paymentFallback(Throwable ex) {
    log.warn("Payment service degraded", ex);
    return PaymentResult.degraded();
}
```

## 监控与可观测性

### 必须暴露的指标

- `http_server_requests_seconds` - HTTP 请求延迟
- `order_created_total` - 订单创建总数
- `payment_success_total` - 支付成功总数
- `seata_transaction_total` - Seata 事务总数
- `outbox_pending_total` - 待发送 Outbox 消息数

### 日志规范

- **INFO**：关键业务事件（订单创建、支付成功）
- **WARN**：降级、重试、性能问题
- **ERROR**：API 失败、事务回滚
- **DEBUG**：详细的调试信息（开发环境）

### 链路追踪

- 使用 Micrometer Tracing
- 每个请求分配唯一的 `traceId`
- Seata 全局事务记录 `xid`

## 测试规范

### 单元测试

- 核心业务逻辑必须有单元测试
- Mock 外部依赖（Feign Client、数据库）
- 测试覆盖率 > 60%

### 集成测试

- 测试分布式事务的正常提交
- 测试分布式事务的回滚
- 测试 Outbox 消息发送
- 测试限流和熔断

### 故障注入测试

使用环境变量触发故障：

```bash
# 测试库存扣减失败
LAB_FAIL_INVENTORY_DEDUCT=true mvn -q -pl distributed/inventory-service spring-boot:run

# 测试支付预扣失败
LAB_FAIL_PAYMENT_PREPARE=true mvn -q -pl distributed/payment-service spring-boot:run
```

## 常见问题处理

### Seata 事务回滚失败

1. 检查 `undo_log` 表是否存在
2. 检查数据库连接池配置
3. 检查 Seata Server 日志
4. 验证 `rollbackFor` 配置

### Nacos 服务注册失败

1. 检查 Nacos Server 是否启动
2. 检查网络连接
3. 检查 `application.yml` 中的 Nacos 地址
4. 查看服务启动日志

### RabbitMQ 消息丢失

1. 确认 Outbox 记录已写入数据库
2. 检查 Outbox Consumer 是否运行
3. 检查 RabbitMQ 连接
4. 查看 `outbox_message` 表的状态

### 读写分离查询旧数据

1. 检查主从延迟（`SHOW SLAVE STATUS`）
2. 使用 `HintManager` 强制读主库
3. 考虑最终一致性设计

## 性能优化

### 数据库优化

- 使用索引优化查询
- 避免 N+1 查询问题
- 使用批量插入
- 合理使用缓存（Redis）

### 服务调用优化

- 使用异步调用（非关键路径）
- 实现超时和重试
- 使用连接池
- 避免循环调用

### Seata 性能优化

- 使用 `AT` 模式而非 `TCC`（除非必要）
- 合理设置全局锁超时
- 定期清理 `undo_log`

## 验证清单

在提交代码前，必须完成以下验证：

- [ ] 模块编译通过：`mvn -f distributed/<module>/pom.xml compile`
- [ ] 单元测试通过：`mvn -f distributed/<module>/pom.xml test`
- [ ] 服务能正常启动并注册到 Nacos
- [ ] 如果涉及 Seata，验证全局事务能正常提交和回滚
- [ ] 如果涉及 Outbox，验证消息能正常发送
- [ ] API 能正常响应（通过 curl 或测试脚本）
- [ ] 监控指标正常暴露（`/actuator/prometheus`）
- [ ] 日志无 ERROR 级别错误
- [ ] 更新了相关文档（如有 API 变更）

## 文档更新

修改代码时，同步更新以下文档（如适用）：

- `distributed/README.md` - 如果影响启动流程、架构说明
- API 文档（`docs/api/`）- 如果有 API 变更
- 运维手册（`docs/runbooks/`）- 如果影响部署或监控
- 技术分析（`docs/analysis/`）- 如果是性能优化或架构演进

## 参考资源

- [Seata 官方文档](https://seata.io/zh-cn/docs/overview/what-is-seata.html)
- [Nacos 官方文档](https://nacos.io/zh-cn/docs/what-is-nacos.html)
- [Sentinel 官方文档](https://sentinelguard.io/zh-cn/docs/introduction.html)
- [ShardingSphere 官方文档](https://shardingsphere.apache.org/document/current/cn/overview/)
- [项目根目录 CLAUDE.md](../CLAUDE.md)
