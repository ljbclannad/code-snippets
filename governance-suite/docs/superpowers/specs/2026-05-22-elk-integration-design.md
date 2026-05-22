# ELK 集成设计文档

**日期：** 2026-05-22  
**作者：** Claude  
**状态：** 已批准

## 1. 概述

为 governance-suite 微服务项目集成 ELK（Elasticsearch + Logstash + Kibana）日志监控系统，实现全面的可观测性，包括业务指标、技术指标和全链路追踪。

### 1.1 目标

- 集中化日志管理，支持跨服务查询和分析
- 实时监控关键业务指标（订单、支付、库存、Outbox）
- 全链路追踪，基于 traceId 关联分布式请求
- 最小化性能影响（异步发送，业务线程不阻塞）

### 1.2 改造范围

**包含的核心服务（6 个）：**
- gateway (8080)
- order-service (8081)
- payment-service (8082)
- inventory-service (8083)
- user-service (8084)
- outbox-consumer (8090)

**不包含：**
- AI 服务（user-profile-service、vector-memory-service、ai-chat-service）保持现状

## 2. 整体架构

### 2.1 日志流向

```
Spring Boot 服务
  ↓ logback-logstash-encoder
  ↓ 异步 TCP 连接 (localhost:50000)
  ↓
Logstash
  ↓ 解析、enrichment、过滤
  ↓
Elasticsearch (localhost:9200)
  ↓
Kibana 可视化 (localhost:5601)
```

### 2.2 核心组件

| 组件 | 版本 | 作用 |
|------|------|------|
| logstash-logback-encoder | 7.4 | 将日志序列化为 JSON 并通过 TCP 发送 |
| AsyncAppender | Logback 内置 | 异步队列，避免阻塞业务线程 |
| Logstash | 8.x | 接收、解析、enrichment、输出到 ES |
| Elasticsearch | 8.x | 日志存储和搜索引擎 |
| Kibana | 8.x | 可视化和查询界面 |

### 2.3 技术选型：方案 1（Logstash TCP Appender）

**选择理由：**
- 性能最优：异步批量发送，对业务影响 < 1ms
- 结构化原生支持：自动序列化 MDC、异常堆栈、自定义字段
- 配置简单：只需修改 logback-spring.xml
- docker-elk 已暴露 50000 端口，直接可用

**替代方案（未采用）：**
- 方案 2（Filebeat 采集文件）：延迟高、结构化困难
- 方案 3（混合模式）：配置复杂、磁盘占用高

## 3. 依赖配置

### 3.1 在 common/pom.xml 中添加

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

**说明：**
- 放在 common 模块，6 个核心服务自动继承
- 版本 7.4 兼容 Spring Boot 3.2.5 和 Logback 1.4.x
- 无额外传递依赖冲突

## 4. Logback 配置

### 4.1 配置文件结构

为每个核心服务创建 `src/main/resources/logback-spring.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="APP_NAME" value="服务名"/>
    
    <!-- 1. CONSOLE appender - 开发环境和降级 -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- 2. LOGSTASH appender - 生产环境 -->
    <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
        <destination>localhost:50000</destination>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${APP_NAME}"}</customFields>
        </encoder>
    </appender>
    
    <!-- 3. ASYNC wrapper - 异步发送 -->
    <appender name="ASYNC_LOGSTASH" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="LOGSTASH"/>
        <queueSize>512</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_LOGSTASH"/>
    </root>
</configuration>
```

### 4.2 关键参数说明

| 参数 | 值 | 说明 |
|------|-----|------|
| queueSize | 512 | 异步队列大小，平衡内存和吞吐 |
| discardingThreshold | 0 | 不丢弃任何级别的日志 |
| neverBlock | true | 队列满时丢弃而不阻塞（性能优先） |
| customFields | service | 添加服务名标识，便于 Kibana 过滤 |

### 4.3 环境隔离（可选）

通过 Spring Profile 控制是否启用 Logstash：

```xml
<springProfile name="dev">
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <!-- dev 环境不发送到 Logstash -->
    </root>
</springProfile>

<springProfile name="prod">
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_LOGSTASH"/>
    </root>
</springProfile>
```

## 5. 结构化业务指标日志

### 5.1 日志输出方式

使用 `net.logstash.logback.argument.StructuredArguments` 输出结构化字段：

```java
import net.logstash.logback.argument.StructuredArguments;

log.info("Order created", 
    StructuredArguments.kv("event", "order_created"),
    StructuredArguments.kv("orderId", orderId),
    StructuredArguments.kv("userId", userId),
    StructuredArguments.kv("amount", totalAmount),
    StructuredArguments.kv("status", "SUCCESS")
);
```

### 5.2 关键指标埋点位置

| 服务 | 埋点位置 | 事件名称 | 关键字段 |
|------|----------|----------|----------|
| order-service | OrderAppService.createOrder() | order_created | orderId, userId, amount, status |
| order-service | OrderAppService.createOrder() 异常 | order_failed | orderId, userId, errorCode, errorMsg |
| payment-service | PaymentService.prepare() | payment_prepared | orderId, userId, amount |
| payment-service | PaymentService.confirm() | payment_confirmed | orderId, userId, amount, duration_ms |
| payment-service | PaymentService 异常 | payment_failed | orderId, userId, errorCode |
| inventory-service | InventoryService.deduct() | inventory_deducted | skuId, quantity, orderId |
| inventory-service | InventoryService.deduct() 失败 | inventory_deduct_failed | skuId, quantity, orderId, retryCount |
| outbox-consumer | OutboxConsumer 状态变更 | outbox_sent / outbox_failed / outbox_dead | outboxId, retryCount, status |
| gateway | AuthTokenGlobalFilter | auth_failed | userId, reason |
| gateway | RateLimiter | rate_limited | clientIp, path |

### 5.3 技术指标埋点

| 指标类型 | 埋点位置 | 事件名称 | 关键字段 |
|----------|----------|----------|----------|
| Seata 全局事务 | @GlobalTransactional 方法 | seata_commit / seata_rollback | xid, duration_ms |
| Sentinel 限流 | Sentinel 回调 | sentinel_blocked | resource, rule, qps |
| Sentinel 熔断 | Sentinel 回调 | sentinel_degraded | resource, rule |
| 幂等锁冲突 | IdempotencyService.tryLock() | idempotency_conflict | key, userId |
| OpenFeign 调用 | Feign 拦截器 | feign_call | targetService, method, duration_ms, status |

### 5.4 JSON 输出示例

```json
{
  "@timestamp": "2026-05-22T10:30:45.123Z",
  "service": "order-service",
  "level": "INFO",
  "thread": "http-nio-8081-exec-1",
  "logger": "com.lx.governance.order.service.OrderAppService",
  "traceId": "abc123def456",
  "message": "Order created",
  "event": "order_created",
  "orderId": "1001",
  "userId": "2001",
  "amount": 99.99,
  "status": "SUCCESS"
}
```

## 6. Logstash Pipeline 配置

### 6.1 修改 docker-elk/logstash/pipeline/logstash.conf

```ruby
input {
  tcp {
    port => 50000
    codec => json_lines
  }
}

filter {
  # 1. 添加接收时间戳
  mutate {
    add_field => { "[@metadata][received_at]" => "%{@timestamp}" }
  }
  
  # 2. 解析服务名和环境
  if [service] {
    mutate {
      add_field => { "[@metadata][index_prefix]" => "governance-%{service}" }
    }
  } else {
    mutate {
      add_field => { "[@metadata][index_prefix]" => "governance-unknown" }
    }
  }
  
  # 3. 提取关键字段
  if [event] {
    mutate {
      add_tag => ["business_metric"]
    }
  }
  
  # 4. 异常堆栈处理
  if [stack_trace] {
    mutate {
      add_tag => ["has_exception"]
    }
  }
  
  # 5. traceId 索引优化
  if [traceId] {
    mutate {
      add_field => { "trace_id" => "%{traceId}" }
    }
  }
}

output {
  elasticsearch {
    hosts => ["elasticsearch:9200"]
    user => "logstash_internal"
    password => "${LOGSTASH_INTERNAL_PASSWORD}"
    index => "%{[@metadata][index_prefix]}-%{+YYYY.MM.dd}"
  }
  
  # 开发调试用（可选）
  # stdout { codec => rubydebug }
}
```

### 6.2 索引命名规则

- `governance-gateway-2026.05.22`
- `governance-order-service-2026.05.22`
- `governance-payment-service-2026.05.22`
- `governance-inventory-service-2026.05.22`
- `governance-user-service-2026.05.22`
- `governance-outbox-consumer-2026.05.22`

**按天滚动，便于管理和清理。**

### 6.3 Filter 说明

- 自动提取 `event` 字段打 `business_metric` 标签，便于业务指标聚合
- 异常日志打 `has_exception` 标签，快速过滤错误
- `traceId` 复制到 `trace_id` 字段，优化 Kibana 查询性能

## 7. Kibana 配置和可视化

### 7.1 创建索引模式

1. 访问 Kibana (http://localhost:5601)
2. Management → Stack Management → Index Patterns
3. 创建模式：`governance-*`
4. 时间字段：`@timestamp`

### 7.2 预定义查询（Saved Searches）

| 查询名称 | KQL 查询语句 | 用途 |
|----------|--------------|------|
| 错误日志 | `level:ERROR OR level:WARN` | 快速定位错误 |
| 业务指标 | `tags:business_metric` | 查看所有业务事件 |
| 订单链路 | `event:order_* AND traceId:xxx` | 追踪订单全流程 |
| Outbox 失败 | `event:outbox_failed` | 监控消息发送失败 |
| 慢请求 | `duration_ms:>3000` | 性能分析 |
| 幂等冲突 | `event:idempotency_conflict` | 重复请求监控 |

### 7.3 关键指标仪表板

**Dashboard 1 - 服务健康度：**
- 各服务日志量趋势（Time Series，按 service 分组）
- ERROR 日志数量（Metric，按 service 分组）
- 平均响应时间（从 duration_ms 计算）
- 服务可用性（基于心跳日志）

**Dashboard 2 - 业务指标：**
- 订单创建成功率：`event:order_created AND status:SUCCESS` / 总数
- 支付成功率：`event:payment_confirmed` / (`event:payment_confirmed` + `event:payment_failed`)
- 库存扣减失败次数：`event:inventory_deduct_failed` 计数
- Outbox 状态分布：按 status 字段聚合（NEW/SENT/FAILED/DEAD）
- 幂等锁冲突趋势：`event:idempotency_conflict` 时间序列

**Dashboard 3 - 链路追踪：**
- 按 traceId 聚合的请求链路（Data Table）
- 跨服务调用耗时分布（Histogram）
- 异常堆栈 Top 10（Terms Aggregation on stack_trace）
- 慢请求 Top 10（按 duration_ms 排序）

### 7.4 告警规则（可选，需要 Kibana Alerting）

| 告警名称 | 触发条件 | 严重级别 |
|----------|----------|----------|
| 错误日志激增 | ERROR 日志 5 分钟内超过 10 条 | High |
| 订单创建成功率低 | 成功率低于 95% | Critical |
| Outbox DEAD 状态 | 出现 DEAD 状态 | High |
| 支付失败率高 | 失败率超过 5% | Critical |
| 慢请求告警 | duration_ms > 5000 的请求超过 5 个/分钟 | Medium |

### 7.5 数据保留策略

- 建议保留 7-30 天
- 通过 Elasticsearch ILM（Index Lifecycle Management）自动删除旧索引
- 配置示例：
  - Hot phase: 1 天（可搜索、可写入）
  - Warm phase: 6 天（只读、压缩）
  - Delete phase: 30 天后删除

## 8. 错误处理和降级策略

### 8.1 Logstash 不可用时的行为

由于配置了 `neverBlock=true`：
- ✅ 日志会被丢弃，**不阻塞业务线程**
- ✅ CONSOLE appender 仍然正常输出，开发环境可见
- ⚠️ 生产环境建议监控 Logstash 连接状态

### 8.2 连接重试机制

`LogstashTcpSocketAppender` 内置重连机制：
- 初始连接失败：每 30 秒重试一次
- 连接断开：自动重连
- 无需额外配置

### 8.3 性能监控

在 `application.yml` 中启用 Logback 状态监听（可选）：

```yaml
logging:
  level:
    net.logstash.logback: WARN
```

### 8.4 故障排查步骤

如果日志未出现在 Kibana：

1. **检查 Logstash 是否监听 50000 端口：**
   ```bash
   netstat -an | grep 50000
   # 或
   docker exec docker-elk-logstash-1 netstat -an | grep 50000
   ```

2. **检查服务日志中是否有连接错误：**
   ```bash
   # 查看服务启动日志
   mvn -q -pl order-service spring-boot:run
   ```

3. **检查 Elasticsearch 索引是否创建：**
   ```bash
   curl localhost:9200/_cat/indices?v | grep governance
   ```

4. **检查 Logstash 日志：**
   ```bash
   docker logs docker-elk-logstash-1
   ```

5. **测试 Logstash TCP 连接：**
   ```bash
   echo '{"message":"test"}' | nc localhost 50000
   ```

## 9. 实施步骤

### 9.1 准备工作

1. 启动 docker-elk：
   ```bash
   cd /Users/lejinbo/IdeaProjects/docker-elk
   docker compose --profile=setup up -d
   docker compose up -d
   ```

2. 验证 ELK 服务：
   - Elasticsearch: http://localhost:9200
   - Kibana: http://localhost:5601
   - Logstash TCP: `nc -zv localhost 50000`

### 9.2 代码改造顺序

1. 修改 `common/pom.xml` 添加依赖
2. 为 6 个核心服务创建 `logback-spring.xml`
3. 在关键业务代码中添加结构化日志埋点
4. 修改 Logstash pipeline 配置
5. 重启 Logstash：`docker restart docker-elk-logstash-1`
6. 启动服务并验证日志输出

### 9.3 验证步骤

1. 启动一个服务（如 order-service）
2. 调用 API 触发业务逻辑
3. 在 Kibana Discover 中查询：`service:order-service`
4. 验证结构化字段是否正确解析
5. 验证 traceId 是否存在
6. 验证业务事件是否有 `business_metric` 标签

## 10. 性能影响评估

### 10.1 预期性能指标

| 指标 | 预期值 | 说明 |
|------|--------|------|
| 业务线程阻塞时间 | < 1ms | 异步队列写入时间 |
| 内存占用增加 | ~10MB/服务 | 异步队列 + 依赖 jar |
| 网络带宽 | ~1KB/条日志 | JSON 序列化后大小 |
| Logstash CPU | ~10-20% | 单核处理能力 ~10k 条/秒 |

### 10.2 压测建议

在生产部署前，建议进行压测：
- 使用 JMeter 模拟高并发请求
- 监控服务 CPU、内存、响应时间
- 监控 Logstash 和 Elasticsearch 资源使用
- 验证日志丢失率（对比 CONSOLE 输出和 Kibana 查询结果）

## 11. 总结

### 11.1 设计亮点

✅ **性能优先**：异步发送，业务影响 < 1ms，队列满时丢弃不阻塞  
✅ **结构化原生**：使用 StructuredArguments，无需 Grok 解析  
✅ **全链路追踪**：基于现有 traceId，无需引入 SkyWalking/Zipkin  
✅ **业务指标完整**：覆盖订单、支付、库存、Outbox、Seata、Sentinel  
✅ **可靠性高**：自动重连，Profile 隔离，降级到 CONSOLE  
✅ **可视化丰富**：Kibana 仪表板覆盖服务健康、业务指标、链路追踪  

### 11.2 后续优化方向

- 集成 Prometheus + Grafana 用于实时指标监控（补充 ELK 的日志分析）
- 引入 APM（如 SkyWalking）实现自动化链路追踪
- 配置 Kibana Alerting 实现告警通知
- 优化 Elasticsearch 索引模板和分片策略
- 实施 ILM 自动化数据生命周期管理

---

**文档版本：** 1.0  
**最后更新：** 2026-05-22
