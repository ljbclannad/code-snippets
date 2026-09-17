# 分布式系统治理

分布式系统治理服务集，展示微服务架构下的服务治理、分布式事务、流量控制等核心能力。

## 目录结构

```
distributed/
├── gateway/              # API 网关 (8080)
├── user-service/         # 用户服务 (8084)
├── order-service/        # 订单服务 (8081) - Seata 发起方
├── payment-service/      # 支付服务 (8082) - Seata 参与者
├── inventory-service/    # 库存服务 (8083) - Seata 参与者
├── outbox-consumer/      # Outbox 消费者 (8090)
└── appointment-service/  # 预约服务 (8089)
```

## 核心技术栈

- **Seata** - 分布式事务（AT 模式）
- **Nacos** - 服务注册与配置中心
- **Sentinel** - 流量控制与熔断降级
- **RabbitMQ** - 消息队列（Outbox 模式）
- **ShardingSphere** - 数据库读写分离
- **Redis** - 缓存和分布式锁

## 模块与端口

| 服务 | 端口 | 说明 |
|-----|------|-----|
| gateway | 8080 | API网关、认证、限流 |
| user-service | 8084 | 用户管理 |
| order-service | 8081 | 订单服务（Seata发起方） |
| payment-service | 8082 | 支付服务（Seata参与者） |
| inventory-service | 8083 | 库存服务（Seata参与者） |
| outbox-consumer | 8090 | 异步消息消费 |
| appointment-service | 8089 | 预约服务 |

## 基础设施端点

| 组件 | 地址 |
|-----|------|
| MySQL | `localhost:3306` |
| Redis | `localhost:36379` |
| RabbitMQ | `http://localhost:15672` (guest/guest) |
| Nacos | `http://localhost:8848/nacos` |
| Seata Server | `localhost:8091` |
| Sentinel | `http://localhost:8858` |

## 快速启动

### 前置条件

确保基础设施已启动：

```bash
# 从项目根目录执行
docker compose up -d
```

### 方式 1: 使用脚本（推荐）

```bash
bash sh/run/start-all-services.sh
```

### 方式 2: 手动启动

最小启动（用户+订单）：

```bash
mvn -q -pl distributed/gateway spring-boot:run
mvn -q -pl distributed/user-service spring-boot:run
mvn -q -pl distributed/order-service spring-boot:run
```

完整启动（包含支付+库存+消息）：

```bash
mvn -q -pl distributed/gateway spring-boot:run
mvn -q -pl distributed/user-service spring-boot:run
mvn -q -pl distributed/order-service spring-boot:run
mvn -q -pl distributed/payment-service spring-boot:run
mvn -q -pl distributed/inventory-service spring-boot:run
mvn -q -pl distributed/outbox-consumer spring-boot:run
```

## 快速验证

### 端到端测试

```bash
bash sh/test/test-create-order.sh
```

### 手动验证

```bash
# 1. 注册用户
curl -X POST http://localhost:8080/api/user/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456","displayName":"Demo User"}'

# 2. 登录获取 Token
TOKEN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456"}' | jq -r '.data.token')

# 3. 创建订单
curl -X POST http://localhost:8080/api/order/order \
  -H "Authorization: $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"productId":"p1","quantity":1,"amountCents":100}'
```

## 后台角色与可信检索范围

后台检索角色为 `ORDER_OPERATOR`、`FINANCE_VIEWER` 与 `INVENTORY_VIEWER`。角色仅由受控运维流程写入用户角色表；公开注册接口不会接收或授予后台角色。

登录令牌携带经过白名单校验的角色。Gateway 会删除客户端提供的 `X-User-Id`、`X-Username` 与 `X-User-Roles`，并只根据验签后的令牌重建这些 Header。下游后台检索入口仍会再次验证 `Authorization` 令牌，不能把 Header、查询参数或请求体中的角色声明作为授权依据。

旧令牌没有角色时仍可用于既有普通认证流程，但不能访问后台检索能力。管理员角色变更通过受控数据库迁移或运维操作执行，公开接口不提供自助提权能力。

## 订单后台读模型

`order-service` 将 `order.created`、`order.updated` 与 `order.cancelled` 的完整订单事实快照经 Outbox 投递到 `biz.events`。订单后台读模型消费者订阅独立队列 `q.order.admin.projection`，并使用直连 MySQL 的非分片数据源写入 `t_order_admin_read_model`，不会经由订单写模型的 ShardingSphere 路由。

读模型按同一订单的 `order_version` 条件写入：重复事件和较旧事件不会覆盖较新快照；取消事件仅将状态投影为 `CANCELLED`，历史订单记录不会删除。该模型是异步最终一致的，初始化环境需要使用 `distributed/init-databases.sql` 创建表结构。

### 后台订单检索

`GET /admin/orders` 支持 `ORDER_OPERATOR`、`FINANCE_VIEWER` 与 `INVENTORY_VIEWER` 三类已认证后台角色。若令牌同时具有多个角色，按 `FINANCE_VIEWER > ORDER_OPERATOR > INVENTORY_VIEWER` 选择字段视图：财务可获得完整客户标识、金额与支付字段；运营和库存仅获得脱敏客户标识；库存响应不会包含金额、支付状态、支付单号或支付更新时间。

检索仅接受 `CREATED_AT`、`AMOUNT_CENTS`、`PAYMENT_UPDATED_AT` 三种 `sort` 值，`direction` 仅接受 `ASC` 或 `DESC`，默认按 `CREATED_AT DESC`。每种排序都以订单号作为稳定次级键，`nextCursor` 只能继续相同筛选条件、排序与方向的列表，不能用于页码跳转或跨条件复用。库存管理员不能按金额或支付字段筛选或排序。

订单后台读模型为异步最终一致投影；列表 cursor 不提供跨服务的一致快照保证。

## 核心能力详解

## 1. 分布式事务（Seata AT 模式）

### 架构说明

**Seata AT 模式流程：**

1. **TM（事务管理器）**：order-service 作为发起方，开启全局事务
2. **RM（资源管理器）**：payment-service 和 inventory-service 作为参与者，注册分支事务
3. **TC（事务协调器）**：Seata Server 协调全局事务的提交或回滚

### 代码示例

```java
@GlobalTransactional(name = "create-order", rollbackFor = Exception.class)
public void createOrder(OrderRequest req) {
    // 1. 创建订单
    orderMapper.insert(order);
    
    // 2. 调用支付服务（通过 Feign）
    paymentClient.prepare(req.getAmountCents());
    
    // 3. 调用库存服务（通过 Feign）
    inventoryClient.deduct(req.getProductId(), req.getQuantity());
    
    // 如果任一步骤失败，Seata 自动回滚所有分支事务
}
```

### 验证 Seata

```bash
# 查看 Seata Server 日志
docker logs gov-seata-server

# 查看服务日志中的全局事务 ID（XID）
tail -f logs/order-service.log | grep "xid"
```

### 故障注入测试

测试库存扣减失败时的回滚：

```bash
LAB_FAIL_INVENTORY_DEDUCT=true mvn -q -pl distributed/inventory-service spring-boot:run
```

测试支付预扣失败时的回滚：

```bash
LAB_FAIL_PAYMENT_PREPARE=true mvn -q -pl distributed/payment-service spring-boot:run
```

## 2. 服务注册与发现（Nacos）

### 架构说明

所有服务启动时自动注册到 Nacos，其他服务通过服务名发现并调用。

### 访问 Nacos 控制台

```
URL: http://localhost:8848/nacos
用户名: nacos
密码: nacos
```

### 验证服务注册

```bash
# API 方式查询
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=order-service

# 控制台查看
访问 http://localhost:8848/nacos → 服务管理 → 服务列表
```

## 3. 流量控制（Sentinel）

### 架构说明

Gateway 集成 Sentinel，对热点 API 实施流量控制和熔断降级。

### 访问 Sentinel 控制台

```
URL: http://localhost:8858
用户名: sentinel
密码: sentinel
```

### 限流规则示例

```java
// 预约抢购接口限流：100 QPS
FlowRule rule = new FlowRule();
rule.setResource("POST:/api/appointment/grab");
rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
rule.setCount(100);
```

### 验证限流

```bash
# 并发测试脚本
bash sh/test/test-grab-flow.sh
```

## 4. 异步消息（RabbitMQ + Outbox 模式）

### 架构说明

**Outbox 模式**确保数据库操作和消息发送的最终一致性：

1. 业务操作和 Outbox 记录在同一事务中写入数据库
2. Outbox Consumer 定期轮询未发送的消息
3. 发送到 RabbitMQ 后标记为已发送

### 代码示例

```java
// 1. 业务服务写入 Outbox
@Transactional
public void createOrder(OrderRequest req) {
    orderMapper.insert(order);
    
    OutboxMessage msg = new OutboxMessage();
    msg.setEventType("ORDER_CREATED");
    msg.setPayload(JsonUtils.toJson(order));
    outboxMapper.insert(msg);
}

// 2. Outbox Consumer 轮询发送
@Scheduled(fixedDelay = 1000)
public void pollAndSend() {
    List<OutboxMessage> pending = outboxMapper.selectPending();
    for (OutboxMessage msg : pending) {
        rabbitTemplate.send(msg.getEventType(), msg.getPayload());
        outboxMapper.markSent(msg.getId());
    }
}
```

### 验证 Outbox

```bash
# 查看 Outbox 表
mysql -h127.0.0.1 -P3306 -uroot -proot123 -e "SELECT * FROM outbox_message LIMIT 10;"

# 查看 RabbitMQ 队列
访问 http://localhost:15672 → Queues
```

## 5. 数据库读写分离（ShardingSphere）

### 架构说明

使用 ShardingSphere 实现主从数据库的读写分离：
- **写操作**：路由到主库（master）
- **读操作**：路由到从库（slave）

### 配置示例

```yaml
spring:
  shardingsphere:
    datasource:
      names: master,slave
      master:
        url: jdbc:mysql://localhost:3306/gov_order?serverTimezone=UTC
        username: root
        password: root123
      slave:
        url: jdbc:mysql://localhost:3307/gov_order?serverTimezone=UTC
        username: root
        password: root123
    rules:
      readwrite-splitting:
        data-sources:
          order-ds:
            write-data-source-name: master
            read-data-source-names: slave
```

### 验证读写分离

```bash
# 运行验证脚本
bash sh/verify/verify-read-write-split.sh
```


## 6. 医院挂号场景（Redis Lua）

### 架构说明

appointment-service 使用 Redis Lua 脚本实现原子化预约：
- 检查库存 + 扣减库存 + 创建订单在一个 Lua 脚本中完成
- Gateway 限流防止恶意抢购
- Redis 缓存热门科室信息

### 核心 Lua 脚本

```lua
local key = KEYS[1]
local delta = tonumber(ARGV[1])
local current = redis.call('GET', key)

if current == false then
    return -1  -- key 不存在
end

local num = tonumber(current)
if num < delta then
    return 0  -- 库存不足
end

redis.call('DECRBY', key, delta)
return 1  -- 扣减成功
```

### 快速启动

```bash
mvn -q -pl distributed/gateway spring-boot:run
mvn -q -pl distributed/appointment-service spring-boot:run
```

### 验证预约

```bash
bash sh/test/test-appointment.sh
bash sh/test/test-grab-flow.sh
```

## 监控与可观测性

### Prometheus 指标

所有服务暴露以下指标：
- JVM 指标（堆内存、GC、线程）
- HTTP 请求指标（QPS、延迟、错误率）
- 业务指标（订单创建、支付成功率等）

### 访问监控

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (admin/admin)
  - Dashboard: "JVM Metrics"
  - Dashboard: "Appointment Services"

### 验证监控

```bash
bash sh/verify/verify-monitoring.sh
```

### 日志

启动脚本会将日志输出到：

```
logs/
├── gateway.log
├── user-service.log
├── order-service.log
├── payment-service.log
├── inventory-service.log
└── outbox-consumer.log
```

查看日志：

```bash
tail -f logs/order-service.log
```

## 停止服务

```bash
# 查看所有运行的服务 PID
cat logs/pids.txt

# 停止所有服务
bash sh/run/stop-all-services.sh
```

## 常见问题

### 1. 服务启动失败

**检查基础设施：**

```bash
docker compose ps
```

确保以下容器运行：
- gov-mysql
- gov-redis
- gov-rabbitmq
- gov-nacos
- gov-seata-server

**检查端口占用：**

```bash
lsof -i :8080
lsof -i :8081
```

### 2. 服务注册到 Nacos 失败

访问 Nacos 控制台检查：`http://localhost:8848/nacos`

检查服务配置中的 Nacos 地址：
- `application.yml` 中的 `spring.cloud.nacos.discovery.server-addr`

### 3. Seata 分布式事务失败

**检查 Seata Server：**

```bash
docker logs gov-seata-server
```

**检查服务日志中的 XID：**

```bash
tail -f logs/order-service.log | grep "xid"
```

**常见问题：**
- Seata Server 未启动
- 服务未正确注册到 Seata
- 数据库 `undo_log` 表未创建

### 4. RabbitMQ 消息未消费

**检查 RabbitMQ：**

访问 `http://localhost:15672` 查看队列状态

**检查 Outbox Consumer：**

```bash
tail -f logs/outbox-consumer.log
```

## 技术债务与改进方向

### 当前限制

1. **Seata AT 模式**：依赖数据库 `undo_log`，对数据库有侵入
2. **Outbox 轮询**：定时轮询数据库，可能有延迟
3. **读写分离**：需要手动配置主从复制

### 未来改进

1. **Seata TCC 模式**：更灵活的补偿机制
2. **Change Data Capture (CDC)**：替代 Outbox 轮询
3. **分库分表**：使用 ShardingSphere 的分片能力

## TCC 新增链路

项目中额外补了一条 Seata TCC 下单链路，入口为：

```bash
curl -X POST http://localhost:8080/api/order/order/tcc \
  -H "Authorization: $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-tcc-1' \
  -d '{"productId":"p1","quantity":1,"amountCents":100}'
```

### TCC 处理点

- `order-service`
  - 本地 `OrderTccAction` 负责订单 `try/confirm/cancel`
  - `try` 写入 `TCC_PREPARED` 订单和 `t_order_tcc_log`
- `payment-service`
  - `PaymentTccAction` 负责支付预留与补偿
  - `try` 写入 `PREPARED` 支付单和 `t_payment_tcc_log`
- `inventory-service`
  - `InventoryTccAction` 负责库存预扣与回补
  - `try` 先扣减库存，再写入 `t_inventory_tcc_log`

### 幂等、空回滚、悬挂方案

- 幂等
  - 每个参与者都用 `order_no` 作为 TCC 业务幂等键
  - `try/confirm/cancel` 先查对应的 `*_tcc_log`，已处理直接返回
- 空回滚
  - `cancel` 发现没有 `try` 日志时，会落一条 `ROLLBACKED` 标记后直接返回
  - 这样可以处理 `cancel` 先于 `try` 到达的场景
- 悬挂
  - `try` 开始前先检查是否存在 `ROLLBACKED` 标记
  - 如果存在，直接拒绝本次 `try`，避免回滚后又再次执行业务预扣

## 开发指南

### 本地开发

1. 启动基础设施：`docker compose up -d`
2. 编译：`mvn clean compile`
3. 启动单个服务：`mvn -q -pl distributed/order-service spring-boot:run`
4. 修改代码后重启服务

### 调试

使用 IDE 调试模式启动，或使用 Maven 调试参数：

```bash
mvn -q -pl distributed/order-service spring-boot:run \
  -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

### 测试

```bash
# 单元测试
mvn -f distributed/order-service/pom.xml test

# 集成测试
bash sh/test/test-create-order.sh
```

## 贡献指南

请参考 [distributed/AGENTS.md](AGENTS.md) 了解开发规范。
# 订单后台读模型支付投影

订单后台读模型分别维护 `order_version` 与 `payment_version`：订单与支付事件只能更新各自拥有的字段，任一事件先到都会以占位行或补全行的方式收敛。`payment_status IS NULL` 表示支付投影尚未知，不能解释为未支付；`PREPARED`、`SUCCEEDED`、`CANCELLED` 才是支付事实源明确给出的状态。

支付服务对 `PREPARED`、`SUCCEEDED` 和 `CANCELLED` 的真实状态变化以本地 `payment_db.t_outbox` 记录 `payment.status.changed` 事件。outbox-consumer 在 broker publisher confirm 后才将订单库或支付库的 Outbox 记录标记为 `SENT`。

`ORDER_OPERATOR`、`FINANCE_VIEWER` 与 `INVENTORY_VIEWER` 可调用 `GET /admin/orders` 检索订单后台读模型。请求必须提供 UTC 的
`createdFrom` 与 `createdTo`，时间窗口不得超过 90 天；可组合使用 `orderNo`、
`customerSearchToken`、`productId`、`status`、`paymentStatus`、金额范围和 `limit`（1–100）。
结果可按 `CREATED_AT`、`AMOUNT_CENTS` 或 `PAYMENT_UPDATED_AT` 排序，方向为 `ASC` 或 `DESC`，每种排序均以
`order_no` 作为稳定次级键；响应的 `nextCursor` 用于继续读取下一页。游标与全部筛选条件、排序及方向绑定，
不能跨条件复用。运营和库存响应只返回脱敏客户标识；财务可返回完整客户标识、金额与支付字段；库存不返回金额
或支付字段，并且不能按金额或支付字段筛选或排序。支付先到创建的占位行和未完成创建时间回填的历史行不会出现在该查询结果中。

读模型是异步最终一致的：在同一稳定读模型状态下，游标分页不会重复或遗漏；不承诺投影更新
期间跨多个请求形成绝对快照。
