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
- order-service：下单（Seata 全局事务发起方）+ Outbox 落库 + MQ 消费更新订单状态
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

### 2）模拟支付成功

```bash
# 你需要从上一步响应中拿到 orderNo
curl -X POST http://localhost:8080/api/payment/confirm/<orderNo>
```

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

## 重要说明（真实生产一定要看）
- Seata + ShardingSphere 的组合在不同版本下兼容性差异较大；本项目主要提供「工程组织方式 + 关键配置入口 + 演示链路」。
- 若你要在生产落地：务必验证 Seata DataSourceProxy 在 ShardingSphereDataSource 上的代理行为、undo_log 落库、以及跨库分片事务边界。
