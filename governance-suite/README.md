# governance-suite

这是一个面向分布式知识学习的 Spring Cloud 演示项目，不以复杂业务为目标，而是用一条可跑通的订单链路，把常见分布式问题拆开学习。

当前主线能力：

- Nacos：服务注册与发现
- Spring Cloud Gateway：统一入口与路由
- OpenFeign + LoadBalancer：服务间调用
- Seata AT：同步调用链路的分布式事务
- RabbitMQ + Outbox：最终一致性与可靠投递
- Redis：幂等、防重、秒杀预扣减
- Sentinel：限流、熔断、降级
- ShardingSphere-JDBC：订单和支付分表

## 学习入口

- [项目定位](./docs/01-overview.md)
- [服务注册与发现](./docs/02-service-discovery.md)
- [分布式事务](./docs/03-distributed-transaction.md)
- [最终一致性与 Outbox](./docs/04-eventual-consistency-outbox.md)
- [幂等、防重与秒杀](./docs/05-idempotency-and-seckill.md)
- [限流、降级与保护](./docs/06-flow-control.md)
- [源码延伸入口（对上游框架源码的解读入口）](./docs/07-source-reading-guide.md)
- [Spring 源码解读入口（对 Spring 框架机制的解读入口）](./docs/08-spring-source-reading-guide.md)
- [Spring 与中间件源码联合解读入口（推荐主阅读路线）](./docs/09-combined-source-reading-guide.md)

> 注意：`源码延伸入口` 不是这些分布式组件的官方源码文档，而是从本项目的接入代码出发，给出的上游框架源码阅读建议和解读入口。
> `Spring 源码解读入口` 也不是 Spring 官方文档，而是从本项目出发，对容器、自动配置、AOP、事务、事件、调度和请求分发这些 Spring 机制的阅读建议。
> `09` 是把 Spring 和中间件两条线合并后的主阅读路线，适合按调用链一起看源码。

## 实验手册

- [Lab 01: 服务注册与发现](./docs/labs/lab-01-nacos.md)
- [Lab 02: Seata 全局事务回滚](./docs/labs/lab-02-seata.md)
- [Lab 03: Outbox 重试与最终一致性](./docs/labs/lab-03-outbox.md)
- [Lab 04: 幂等与秒杀防重](./docs/labs/lab-04-idempotency-and-seckill.md)

## 模块

- `common`：统一返回、TraceId、异常处理、Nacos 注册重试
- `gateway`：网关路由、RedisRateLimiter、Sentinel Gateway Block
- `order-service`：下单主流程、Seata 发起方、Outbox 落库、支付事件消费
- `inventory-service`：库存扣减、乐观锁、库存侧幂等
- `payment-service`：支付准备、支付确认、支付成功事件发布
- `outbox-consumer`：轮询 `t_outbox` 并可靠投递到 RabbitMQ

## 学习用故障注入

为了把项目从“能跑 demo”变成“能做实验”，保留了 3 个最小故障开关：

- `LAB_FAIL_INVENTORY_DEDUCT=true`
- `LAB_FAIL_PAYMENT_PREPARE=true`
- `LAB_FAIL_PAYMENT_CONFIRM=true`
- `LAB_FAIL_OUTBOX_PUBLISH=true`

这些开关默认关闭，适合配合实验手册观察事务回滚、消息重试和错误传播。

## 一键启动基础设施

```bash
docker compose up -d
```

- MySQL: localhost:3306 (root/root)
- Redis: localhost:36379
- RabbitMQ: localhost:5672 / 控制台 http://localhost:15672 (guest/guest)
- Nacos: http://localhost:8848 (MySQL 持久化，默认账号 nacos/nacos)
- Seata: localhost:8091
- Sentinel Dashboard: http://localhost:8858

> 首次初始化或变更数据库脚本后，建议先执行：
> `docker compose down -v && docker compose up -d`

## 编译

```bash
mvn -T 1C clean package -DskipTests
```

## 打包部署（本地）

```bash
# 1) 打包所有模块
mvn -T 1C clean package -DskipTests

# 2) 启动 governance-suite 这一组基础设施
docker compose up -d
```

如果你在仓库根目录执行，也可以显式指定 compose 组：

```bash
docker compose -f governance-suite/docker-compose.yml -p governance-suite up -d
```

## 启动服务

按 Nacos 模式启动：

- `gateway` `8080`
- `user-service` `8084`
- `inventory-service` `8083`
- `payment-service` `8082`
- `order-service` `8081`
- `outbox-consumer` `8090`

建议先启动基础设施，再启动业务服务。若要做实验，可在启动前设置对应的 `LAB_FAIL_*` 环境变量。

### 命令行启动

在项目根目录执行：

```bash
mvn -q -pl gateway spring-boot:run
mvn -q -pl user-service spring-boot:run
mvn -q -pl inventory-service spring-boot:run
mvn -q -pl payment-service spring-boot:run
mvn -q -pl order-service spring-boot:run
mvn -q -pl outbox-consumer spring-boot:run
```

如果你想分终端启动，建议顺序如下：

```bash
# Terminal 1
mvn -q -pl gateway spring-boot:run

# Terminal 2
mvn -q -pl user-service spring-boot:run

# Terminal 3
mvn -q -pl inventory-service spring-boot:run

# Terminal 4
mvn -q -pl payment-service spring-boot:run

# Terminal 5
mvn -q -pl order-service spring-boot:run

# Terminal 6
mvn -q -pl outbox-consumer spring-boot:run
```

### 带实验开关启动

例如模拟库存扣减失败：

```bash
LAB_FAIL_INVENTORY_DEDUCT=true mvn -q -pl inventory-service spring-boot:run
```

例如模拟支付准备失败：

```bash
LAB_FAIL_PAYMENT_PREPARE=true mvn -q -pl payment-service spring-boot:run
```

例如模拟 Outbox 投递失败：

```bash
LAB_FAIL_OUTBOX_PUBLISH=true mvn -q -pl outbox-consumer spring-boot:run
```

### 停止服务

如果服务是前台启动的，直接在对应终端执行：

```bash
Ctrl + C
```

如果你想按端口停止本地服务，可执行：

```bash
lsof -ti :8080 | xargs kill
lsof -ti :8081 | xargs kill
lsof -ti :8082 | xargs kill
lsof -ti :8083 | xargs kill
lsof -ti :8084 | xargs kill
lsof -ti :8090 | xargs kill
```

也可以一次性停止这 5 个端口上的 Java 进程：

```bash
lsof -ti :8080 -i :8081 -i :8082 -i :8083 -i :8084 -i :8090 | xargs kill
```

## 基础验证链路

### 1）下单（走网关）

```bash
curl -X POST http://localhost:8080/api/order/order \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"customerId":"c1","productId":"p1","quantity":1,"amountCents":100}'
```

### 1.1）下单 v2（Redis Lua 原子预扣 + Stream 入队）

先准备秒杀库存（示例 sku=1001）：

```bash
redis-cli -p 36379 set seckill:stock:sku:1001 5
redis-cli -p 36379 del seckill:buyers:sku:1001
```

调用：

```bash
curl -X POST http://localhost:8080/api/order/order/v2 \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: req-1001-u1' \
  -d '{"customerId":"u1","productId":"1001","quantity":1,"amountCents":100}'
```

返回 `code=OK` 表示已原子完成：校验库存、去重购买、`DECR` 扣库存、`XADD` 入 `seckill:stream:orders`。

`order-service` 内置了 `@Scheduled(fixedDelay = 50)` 的 stream 消费器，会把消息落到：
- `t_order_*`（创建 `UNPAID` 订单）
- `t_outbox`（写 `order.created.v2` 事件）
- `inventory-service`（调用 `/deduct`，由库存服务执行 `t_stock` 乐观锁扣减）

另外通过 `t_seckill_order_guard.uk_user_sku` 做 DB 侧幂等防重。

### 2）模拟支付成功

```bash
# 你需要从上一步响应中拿到 orderNo
curl -X POST http://localhost:8080/api/payment/confirm/<orderNo>
```

也可以走 `order-service` 的支付回调通道（默认 PASS，统一走 outbox）：

```bash
curl -X POST http://localhost:8080/api/order/order/payment/callback/<orderNo>
```

该链路会先写 `t_outbox(status=NEW)`，随后由 `outbox-consumer` 轮询发布到 RabbitMQ；`order-service` 消费后手动 `ack` 并更新 `t_order.status=PAID`。

## Outbox 状态说明
- `NEW`：待首次投递
- `FAILED`：投递失败，等待 `next_retry_at` 到期后重试
- `SENT`：已成功投递到 RabbitMQ
- `DEAD`：超过 `outbox.max-retries` 后停止自动重试，等待人工处理

默认配置在 [outbox-consumer/src/main/resources/application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/outbox-consumer/src/main/resources/application.yml)：

```yaml
outbox:
  poll-interval-ms: 1000
  max-retries: 5
  retry-delay-seconds: 10
```

### 查看 Outbox 投递情况

```bash
mysql -uroot -proot -h127.0.0.1 -P3306 order_db -e "
SELECT id, event_type, status, retry_count, next_retry_at, sent_at, error_reason
FROM t_outbox
ORDER BY id DESC
LIMIT 10;
"
```

### 演示 Outbox 重试

一种方式是先停掉 RabbitMQ，再触发支付回调或支付成功事件：

```bash
docker stop gov-rabbitmq
curl -X POST http://localhost:8080/api/order/order/payment/callback/<orderNo>
```

此时可在 `t_outbox` 里看到状态从 `NEW` 变成 `FAILED`，并记录 `retry_count` 与 `next_retry_at`。

另一种方式是直接打开实验开关并重启 `outbox-consumer`：

```bash
export LAB_FAIL_OUTBOX_PUBLISH=true
```

恢复正常后关闭该开关并重启服务。

恢复 RabbitMQ 后：

```bash
docker start gov-rabbitmq
```

等待下一次轮询后，记录会自动从 `FAILED` 变成 `SENT`。

### 3）查订单状态

```bash
curl http://localhost:8080/api/order/order/<orderNo>
```

看到 status=PAID 说明：MQ 消费链路走通。

## 这个项目适合学什么

- 为什么要有注册中心，而不是全写死地址
- 为什么同步链路适合 Seata，异步链路适合 Outbox
- 为什么幂等不能只靠前端按钮防重
- 为什么秒杀要先削峰再落库
- 为什么限流和 fallback 是保护系统，不是业务成功

## Gateway IP 限流验证

`gateway` 已启用按客户端 IP 的 RedisRateLimiter（默认 `replenish-rate=5`、`burst-capacity=10`）。

可用下面命令快速压测（同一台机器同一 IP）：

```bash
for i in {1..30}; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/order/order/not-exist
done
```

你会看到部分请求返回 `429`（被限流），说明 IP 限流已生效。

## 重要说明

- Seata + ShardingSphere 的组合在不同版本下兼容性差异较大；本项目主要提供学习链路和实验入口。
- Outbox 展示的是最终一致性，不代表所有消息场景都应这样实现。
- 这些方案的价值在于理解“解决什么问题”和“付出什么成本”，不要把它们当作所有场景的默认答案。
