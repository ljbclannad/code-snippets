# idgen-demo：分布式主键（Snowflake + 号段）一键切换 + 灰度 + 健康检查 + 指标 + MyBatis

## 1. 运行环境
- JDK 17+
- Maven 3.8+
- Docker（用于 MySQL/Redis）

## 2. 一键启动依赖
```bash
docker compose up -d
```

## 3. 启动应用
```bash
mvn spring-boot:run
```

默认端口 `8080`。

## 4. 快速验证
### 4.1 创建订单（会生成分布式主键）
```bash
curl -X POST "http://localhost:8080/api/orders" \
  -H "Content-Type: application/json" \
  -d '{"userId": 1001, "amount": 12.34}'
```

### 4.2 查询订单
```bash
curl "http://localhost:8080/api/orders/{id}"
```

## 5. 一键切换策略（雪花 <-> 号段）
> 这是“运行时切换”，不需要重启。适合快速止血/降级/回滚。

```bash
# 切换到 SEGMENT
curl -X PUT "http://localhost:8080/admin/idgen/strategy" \
  -H "Content-Type: application/json" \
  -d '{"strategy":"SEGMENT","grayEnabled":false,"snowflakePercent":0}'

# 切换到 SNOWFLAKE
curl -X PUT "http://localhost:8080/admin/idgen/strategy" \
  -H "Content-Type: application/json" \
  -d '{"strategy":"SNOWFLAKE","grayEnabled":false,"snowflakePercent":100}'
```

### 灰度（按 userId 稳定分流）
```bash
# 开启灰度：80% Snowflake, 20% Segment
curl -X PUT "http://localhost:8080/admin/idgen/strategy" \
  -H "Content-Type: application/json" \
  -d '{"strategy":"SNOWFLAKE","grayEnabled":true,"snowflakePercent":80}'
```

## 6. 健康检查 & 指标
- 健康检查：`GET /actuator/health`
  - 会展示 workerId 租约、segment 分配状态等
- Prometheus 指标：`GET /actuator/prometheus`
  - 关键指标：idgen.requests、idgen.clock_backwards、idgen.worker_lease_lost、idgen.segment_allocate 等

## 7. 关于“分库分表路由”
本 demo 只演示主键生成与落库。
你在 ShardingSphere-JDBC 中通常会用：
- `order_id` 作为分片键（取模 / 时间范围等）
- 本 demo 的 ID：
  - 雪花 ID：趋势递增 + 分布较均匀
  - 号段 ID：严格递增
两者都可作为分片键；若担心两种策略混用影响分布，可以仅用于“降级窗口”。

