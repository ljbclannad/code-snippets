# governance-suite

组件：
- Spring Cloud Gateway
- Sentinel（限流/熔断/降级示例：Gateway Block + @SentinelResource + Feign fallback）
- Seata AT（全局事务示例：下单 -> 扣库存 -> 创建支付单）
- ShardingSphere-JDBC（订单/支付分表 t_*_0/t_*_1）
- Redis（幂等 Key）
- RabbitMQ（支付成功事件 + Outbox 可靠投递）
- MySQL（3 个业务库 + seata 库 + nacos_config）

## 模块
- common：统一返回、TraceId 过滤器、全局异常处理
- gateway：网关路由、Sentinel Gateway Block 统一 JSON
- order-service：下单（Seata 全局事务发起方）+ Snowflake 订单号 + Outbox 落库 + MQ 消费更新订单状态
- inventory-service：扣库存（乐观锁 version）
- payment-service：支付准备（幂等）+ 支付确认发送 MQ 事件
- outbox-consumer：轮询 t_outbox 发送到 RabbitMQ（可靠投递样板）

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
按顺序启动（本地运行即可）：
- gateway (8080)
- inventory-service (8083)
- payment-service (8082)
- order-service (8081)
- outbox-consumer (8090)

也可以都用 IDEA 直接 Run。

## 验证链路

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

### 3）查订单状态

```bash
curl http://localhost:8080/api/order/order/<orderNo>
```

看到 status=PAID 说明：MQ 消费链路走通。

## 治理点位在哪里？
- 超时：Feign/RPC/网关层（示例里保留了配置入口）
- 重试：建议仅对幂等接口做有限次重试（这里用 Idempotency-Key 演示）
- 熔断/降级：Sentinel（Feign fallback / @SentinelResource fallback）
- 限流：Sentinel（网关 block / 热点参数）+ Gateway RedisRateLimiter（粗粒度）

## Gateway IP 限流验证

`gateway` 已启用按客户端 IP 的 RedisRateLimiter（默认 `replenish-rate=5`、`burst-capacity=10`）。

可用下面命令快速压测（同一台机器同一 IP）：

```bash
for i in {1..30}; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/order/order/not-exist
done
```

你会看到部分请求返回 `429`（被限流），说明 IP 限流已生效。

## 重要说明（真实生产一定要看）
- Seata + ShardingSphere 的组合在不同版本下兼容性差异较大；本项目主要提供「工程组织方式 + 关键配置入口 + 演示链路」。
- 若你要在生产落地：务必验证 Seata DataSourceProxy 在 ShardingSphereDataSource 上的代理行为、undo_log 落库、以及跨库分片事务边界。
