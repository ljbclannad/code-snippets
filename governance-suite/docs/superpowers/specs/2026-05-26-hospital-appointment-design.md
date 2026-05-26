---
title: 医院挂号场景业务设计
date: 2026-05-26
author: Claude
status: approved
---

# 医院挂号场景业务设计

## 1. 项目概述

### 1.1 背景

在现有的 governance-suite 分布式系统学习项目基础上，新增医院挂号场景业务，演示高并发场景下的完整治理方案。

### 1.2 设计目标

实现医院挂号场景的 10 个治理层次：

1. **放号瞬间入口限流** - 按批次维度限流（scheduleId + slotTime）
2. **单用户防刷** - 按 userId 限流
3. **抢号前的库存保护** - Redis + Lua 原子扣减
4. **抢到号源后的订单创建** - 幂等 + 补偿
5. **支付前的号源占用** - 超时释放
6. **支付成功后的异步处理** - MQ 解耦
7. **取消挂号/退款后的库存回补** - 幂等回补
8. **热门科室和普通科室分开治理** - 差异化配置
9. **数据库兜底保护** - 缓存优先
10. **失败补偿和人工兜底** - 最终一致性

### 1.3 实现方案

**方案选择**：一次性完整实现所有功能

**服务模块**：创建独立的 `appointment-service` 模块（端口 8089）

**核心特性**：
- 完全独立的服务，与现有订单服务平行
- 复用现有基础设施（Gateway、Redis、Seata、Outbox）
- 不修改原有业务逻辑

---

## 2. 整体架构设计

### 2.1 服务定位

**appointment-service (端口 8089)**
- 独立的挂号服务，与现有订单服务平行
- 通过 Gateway 接入，复用认证、限流等基础设施
- 注册到 Nacos，支持服务发现

### 2.2 架构图

```
客户端 → Gateway(8080) 
           ├→ 双层限流（scheduleId + userId）
           ├→ Token 校验
           └→ appointment-service(8089)
                ├→ Redis（库存 + 幂等 + Stream）
                ├→ MySQL（排班 + 订单 + 回补记录）
                ├→ Outbox 表（异步通知）
                └→ 定时任务（超时取消 + Stream 消费）
```

### 2.3 核心流程

1. **查询排班** → 从 MySQL 查询可用排班，返回号源余量
2. **抢号** → Gateway 限流 → Redis Lua 原子扣减 → 写入 Stream → 返回成功
3. **异步落库** → Stream 消费器读取 → 创建订单（状态 RESERVED）
4. **支付** → 更新订单状态为 PAID → 写入 Outbox
5. **异步通知** → outbox-consumer 投递 MQ → appointment-service 消费（打日志模拟通知）
6. **超时取消** → 定时任务扫描超时订单 → 更新状态 CANCELLED → 回补库存
7. **主动取消** → 用户调用取消接口 → 幂等检查 → 更新状态 → 回补库存

### 2.4 技术栈

| 类别 | 技术 | 说明 |
|------|------|------|
| **框架** | Spring Boot 3.4.0 | 与现有服务保持一致 |
| **服务治理** | Nacos 2.x | 服务注册与发现 |
| **数据库** | MySQL 8.0 | 排班、订单、回补日志 |
| **缓存** | Redis 7.0 | 库存、幂等、Stream |
| **消息** | RabbitMQ 3.x | 异步通知 |
| **限流** | RedisRateLimiter | Gateway 双层限流 |
| **监控** | Prometheus + Micrometer | 业务指标监控 |

---

## 3. 数据库设计

### 3.1 核心表结构

#### 3.1.1 t_department（科室表）

```sql
CREATE TABLE t_department (
    id BIGINT PRIMARY KEY COMMENT '主键',
    dept_code VARCHAR(50) NOT NULL UNIQUE COMMENT '科室编码',
    dept_name VARCHAR(100) NOT NULL COMMENT '科室名称',
    is_hot TINYINT NOT NULL DEFAULT 0 COMMENT '是否热门科室，1=是 0=否',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_is_hot (is_hot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='科室表';
```

#### 3.1.2 t_doctor（医生表）

```sql
CREATE TABLE t_doctor (
    id BIGINT PRIMARY KEY COMMENT '主键',
    doctor_code VARCHAR(50) NOT NULL UNIQUE COMMENT '医生编码',
    doctor_name VARCHAR(100) NOT NULL COMMENT '医生姓名',
    dept_id BIGINT NOT NULL COMMENT '所属科室',
    title VARCHAR(50) NOT NULL COMMENT '职称：主任医师/副主任医师/主治医师',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_dept_id (dept_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生表';
```

#### 3.1.3 t_schedule（排班表）

```sql
CREATE TABLE t_schedule (
    id BIGINT PRIMARY KEY COMMENT '主键，Snowflake ID',
    doctor_id BIGINT NOT NULL COMMENT '医生ID',
    dept_id BIGINT NOT NULL COMMENT '科室ID',
    schedule_date DATE NOT NULL COMMENT '排班日期',
    time_slot VARCHAR(20) NOT NULL COMMENT '时间段：morning/afternoon/evening',
    total_quota INT NOT NULL COMMENT '总号源数',
    available_quota INT NOT NULL COMMENT '剩余号源数，用于 DB 兜底查询',
    fee_cents INT NOT NULL COMMENT '挂号费，单位分',
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/FULL/CANCELLED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_doctor_date_slot (doctor_id, schedule_date, time_slot),
    INDEX idx_dept_date (dept_id, schedule_date),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排班表';
```

#### 3.1.4 t_appointment_order（挂号订单表）

```sql
CREATE TABLE t_appointment_order (
    id BIGINT PRIMARY KEY COMMENT '主键，Snowflake ID',
    order_no VARCHAR(64) NOT NULL UNIQUE COMMENT '订单号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    schedule_id BIGINT NOT NULL COMMENT '排班ID',
    dept_id BIGINT NOT NULL COMMENT '科室ID',
    doctor_id BIGINT NOT NULL COMMENT '医生ID',
    appointment_date DATE NOT NULL COMMENT '就诊日期',
    time_slot VARCHAR(20) NOT NULL COMMENT '时间段',
    fee_cents INT NOT NULL COMMENT '挂号费，单位分',
    status VARCHAR(20) NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED/PAID/CANCELLED/REFUNDED',
    reserved_at DATETIME NOT NULL COMMENT '预约时间',
    paid_at DATETIME COMMENT '支付时间',
    cancelled_at DATETIME COMMENT '取消时间',
    cancel_reason VARCHAR(200) COMMENT '取消原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_schedule_id (schedule_id),
    INDEX idx_status (status),
    INDEX idx_reserved_at (reserved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂号订单表';
```

#### 3.1.5 t_appointment_idempotency（幂等表）

```sql
CREATE TABLE t_appointment_idempotency (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    idempotency_key VARCHAR(128) NOT NULL UNIQUE COMMENT '幂等键',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    schedule_id BIGINT NOT NULL COMMENT '排班ID',
    order_no VARCHAR(64) NOT NULL COMMENT '订单号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_schedule (user_id, schedule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂号幂等表';
```

#### 3.1.6 t_quota_refund_log（库存回补日志表）

```sql
CREATE TABLE t_quota_refund_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    schedule_id BIGINT NOT NULL COMMENT '排班ID',
    order_no VARCHAR(64) NOT NULL UNIQUE COMMENT '订单号，防止重复回补',
    refund_type VARCHAR(20) NOT NULL COMMENT 'TIMEOUT/USER_CANCEL/DOCTOR_CANCEL/SYSTEM',
    refunded_at DATETIME NOT NULL COMMENT '回补时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_schedule_id (schedule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存回补日志表';
```

### 3.2 预置测试数据

**科室数据**：
- 内科（普通）
- 外科（普通）
- 儿科（热门，is_hot=1）
- 妇科（普通）
- 骨科（普通）

**医生数据**：
- 每个科室 2-3 名医生
- 职称分布：主任医师、副主任医师、主治医师

**排班数据**：
- 未来 7 天（从当前日期开始）
- 每天 3 个时段：morning（08:00-12:00）、afternoon（14:00-18:00）、evening（18:00-21:00）
- 热门科室每个排班 20 个号源
- 普通科室每个排班 50 个号源
- 挂号费：主任医师 500 分，副主任医师 300 分，主治医师 200 分

---

## 4. Redis 数据结构与 Lua 脚本

### 4.1 Redis Key 设计

#### 4.1.1 号源库存

```
Key: appointment:stock:{scheduleId}
Type: String
Value: 剩余号源数（整数）
TTL: 排班日期 + 1天（自动过期）
```

**示例**：
```
appointment:stock:123456 = "15"
```

#### 4.1.2 用户去重集合

```
Key: appointment:buyers:{scheduleId}
Type: Set
Value: userId 集合
TTL: 排班日期 + 1天
```

**示例**：
```
appointment:buyers:123456 = {1001, 1002, 1003}
```

#### 4.1.3 抢号请求队列

```
Key: appointment:stream:orders
Type: Stream
Value: {userId, scheduleId, timestamp, requestId}
消费组: appointment-consumer-group
```

**示例**：
```
XADD appointment:stream:orders * userId 1001 scheduleId 123456 requestId req-001 timestamp 1716700000000
```

#### 4.1.4 幂等锁（复用现有 IdempotencyService）

```
Key: idempotency:appointment:{requestId}
Type: String
Value: "1"
TTL: 1小时
```

#### 4.1.5 库存回补幂等

```
Key: idempotency:refund:{orderNo}
Type: String
Value: "1"
TTL: 24小时
```

#### 4.1.6 科室热门标识缓存

```
Key: appointment:dept:hot:{deptId}
Type: String
Value: "1" 或 "0"
TTL: 1小时
```

### 4.2 Lua 脚本设计

#### 4.2.1 抢号脚本（appointment_grab.lua）

```lua
-- KEYS[1]: appointment:stock:{scheduleId}
-- KEYS[2]: appointment:buyers:{scheduleId}
-- KEYS[3]: appointment:stream:orders
-- ARGV[1]: userId
-- ARGV[2]: scheduleId
-- ARGV[3]: requestId
-- ARGV[4]: timestamp

-- 1. 检查库存
local stock = redis.call('GET', KEYS[1])
if not stock or tonumber(stock) <= 0 then
    return {err = 'SOLD_OUT'}
end

-- 2. 检查用户是否已抢过
local exists = redis.call('SISMEMBER', KEYS[2], ARGV[1])
if exists == 1 then
    return {err = 'ALREADY_GRABBED'}
end

-- 3. 扣减库存
redis.call('DECR', KEYS[1])

-- 4. 记录用户
redis.call('SADD', KEYS[2], ARGV[1])

-- 5. 写入 Stream
redis.call('XADD', KEYS[3], '*', 
    'userId', ARGV[1],
    'scheduleId', ARGV[2],
    'requestId', ARGV[3],
    'timestamp', ARGV[4])

return {ok = 'SUCCESS'}
```

#### 4.2.2 库存回补脚本（appointment_refund.lua）

```lua
-- KEYS[1]: appointment:stock:{scheduleId}
-- KEYS[2]: appointment:buyers:{scheduleId}
-- ARGV[1]: userId

-- 1. 增加库存
redis.call('INCR', KEYS[1])

-- 2. 移除用户记录
redis.call('SREM', KEYS[2], ARGV[1])

return 1
```

### 4.3 库存预热策略

#### 4.3.1 启动时预热

**时机**：appointment-service 启动时执行

**逻辑**：
```java
@PostConstruct
public void preloadStock() {
    // 1. 查询未来 7 天的所有 AVAILABLE 排班
    LocalDate startDate = LocalDate.now();
    LocalDate endDate = startDate.plusDays(7);
    List<Schedule> schedules = scheduleRepository.findByDateRangeAndStatus(
        startDate, endDate, ScheduleStatus.AVAILABLE);
    
    // 2. 批量写入 Redis
    for (Schedule schedule : schedules) {
        String key = "appointment:stock:" + schedule.getId();
        redisTemplate.opsForValue().set(key, 
            String.valueOf(schedule.getAvailableQuota()),
            Duration.ofDays(schedule.getScheduleDate().toEpochDay() - startDate.toEpochDay() + 1));
    }
    
    log.info("库存预热完成，共预热 {} 个排班", schedules.size());
}
```

#### 4.3.2 定时刷新

**频率**：每小时执行一次

**逻辑**：
```java
@Scheduled(cron = "0 0 * * * ?")
public void refreshStock() {
    // 刷新即将开始的排班库存（未来 1 小时内）
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime oneHourLater = now.plusHours(1);
    
    List<Schedule> schedules = scheduleRepository.findUpcomingSchedules(now, oneHourLater);
    
    for (Schedule schedule : schedules) {
        String key = "appointment:stock:" + schedule.getId();
        // 从 DB 重新加载库存
        redisTemplate.opsForValue().set(key, String.valueOf(schedule.getAvailableQuota()));
    }
}
```

---


## 5. Gateway 双层限流设计

### 5.1 限流维度

#### 5.1.1 第一层：批次热点限流（按 scheduleId）

**目标**：保护下游服务，防止单个排班的瞬时洪峰

**Key 格式**：`ratelimit:schedule:{scheduleId}`

**限流参数**：
- **热门科室排班**：
  - `replenishRate=20`（每秒补充 20 个令牌）
  - `burstCapacity=40`（令牌桶容量 40）
- **普通科室排班**：
  - `replenishRate=10`
  - `burstCapacity=20`

**判断逻辑**：
1. 从请求参数中提取 `scheduleId`
2. 查询 Redis 缓存：`appointment:dept:hot:{deptId}`
3. 根据 `is_hot` 字段选择对应的限流参数

#### 5.1.2 第二层：单用户防刷限流（按 userId）

**目标**：防止脚本刷、用户连点、恶意重试

**Key 格式**：`ratelimit:user:{userId}`

**限流参数**：
- `replenishRate=2`（每秒补充 2 个令牌）
- `burstCapacity=5`（令牌桶容量 5）

**说明**：正常用户每秒最多发起 2 次请求，短时突发最多 5 次。

### 5.2 限流触发路径

**仅对抢号接口生效**：`POST /api/appointment/grab`

**其他接口不限流**：
- `GET /api/appointment/schedules`（查询排班）
- `GET /api/appointment/orders`（查询订单）
- `POST /api/appointment/pay/{orderNo}`（支付）
- `POST /api/appointment/cancel/{orderNo}`（取消）

### 5.3 限流响应格式

```json
{
  "success": false,
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "请求过于频繁，请稍后再试",
  "traceId": "1234567890abcdef"
}
```

**HTTP 状态码**：429 Too Many Requests

---

## 6. 核心业务流程设计

### 6.1 查询排班接口

**路径**：`GET /api/appointment/schedules`

**请求参数**：
- `deptId`（可选）：科室ID
- `doctorId`（可选）：医生ID
- `startDate`（必填）：开始日期，格式 yyyy-MM-dd
- `endDate`（必填）：结束日期，格式 yyyy-MM-dd

**业务逻辑**：
1. 参数校验（日期范围不超过 30 天）
2. 从 MySQL 查询符合条件的排班（status=AVAILABLE）
3. 批量从 Redis 获取实时库存：`GET appointment:stock:{scheduleId}`
4. 如果 Redis 无数据，回退到 DB 的 available_quota 字段
5. 返回排班列表（包含医生、科室、时间、剩余号源）

**响应示例**：
```json
{
  "success": true,
  "data": [
    {
      "scheduleId": 123456,
      "doctorName": "张医生",
      "deptName": "儿科",
      "title": "主任医师",
      "scheduleDate": "2026-05-27",
      "timeSlot": "morning",
      "availableQuota": 15,
      "feeCents": 500
    }
  ]
}
```

### 6.2 抢号接口

**路径**：`POST /api/appointment/grab`

**请求头**：
- `Authorization: Bearer {token}`（必填）
- `Idempotency-Key: {requestId}`（必填）

**请求体**：
```json
{
  "scheduleId": 123456
}
```

**业务流程**：
1. Gateway 双层限流检查（scheduleId + userId）
2. 幂等检查：`idempotency:appointment:{requestId}`
3. 执行 Redis Lua 脚本（检查库存 + 去重 + 扣减 + 写入 Stream）
4. 返回成功响应

**响应示例**：
```json
{
  "success": true,
  "message": "抢号成功，请在15分钟内完成支付",
  "data": {
    "scheduleId": 123456,
    "expireAt": "2026-05-26T12:15:00"
  }
}
```

**异常响应**：
- `SOLD_OUT` → "号源已抢完"
- `ALREADY_GRABBED` → "您已抢过该排班"
- `RATE_LIMIT_EXCEEDED` → "请求过于频繁"

### 6.3 Stream 消费器（异步落库）

**执行频率**：每 50ms 执行一次

**业务逻辑**：
1. 从 Stream 读取消息（XREADGROUP，每次最多 100 条）
2. 批量处理消息：
   - 生成订单号（Snowflake）
   - 创建订单记录（status=RESERVED）
   - 写入幂等表（idempotency_key, order_no）
   - ACK 消息（XACK）
3. DB 唯一约束兜底（t_appointment_idempotency.idempotency_key UNIQUE）

**消费组初始化**：
```java
@PostConstruct
public void initConsumerGroup() {
    redisTemplate.opsForStream().createGroup(
        "appointment:stream:orders", 
        "appointment-consumer-group");
}
```

---

## 7. 支付与取消流程设计

### 7.1 支付成功接口

**路径**：`POST /api/appointment/pay/{orderNo}`

**请求头**：`Authorization: Bearer {token}`

**业务流程**：
1. 查询订单（校验订单存在、状态为 RESERVED、用户匹配）
2. 幂等检查：`idempotency:pay:{orderNo}`
3. 更新订单状态为 PAID，设置 paid_at
4. 写入 Outbox（event_type: 'appointment.paid'）
5. 返回成功

**Outbox Payload**：
```json
{
  "orderNo": "APT123456",
  "userId": 1001,
  "scheduleId": 123456,
  "doctorName": "张医生",
  "deptName": "儿科",
  "appointmentDate": "2026-05-27",
  "timeSlot": "morning",
  "paidAt": "2026-05-26T11:30:00"
}
```

### 7.2 用户主动取消接口

**路径**：`POST /api/appointment/cancel/{orderNo}`

**请求头**：`Authorization: Bearer {token}`

**请求体**：
```json
{
  "reason": "临时有事，无法就诊"
}
```

**业务流程**：
1. 查询订单（校验订单存在、用户匹配、状态为 RESERVED）
2. 回补幂等检查：`idempotency:refund:{orderNo}`
3. 更新订单状态为 CANCELLED，设置 cancelled_at 和 cancel_reason
4. 执行 Redis Lua 回补脚本（INCR 库存 + SREM 用户）
5. 记录回补日志（refund_type: 'USER_CANCEL'）
6. 返回成功

**限制**：已支付订单（status=PAID）不可取消（简化处理）

### 7.3 超时取消定时任务

**执行频率**：每分钟执行一次

**业务逻辑**：
1. 扫描超时订单：
   ```sql
   SELECT * FROM t_appointment_order
   WHERE status = 'RESERVED'
     AND reserved_at < NOW() - INTERVAL 15 MINUTE
   LIMIT 100
   ```
2. 批量处理每个订单：
   - 回补幂等检查（idempotency:refund:{orderNo}）
   - 更新订单状态为 CANCELLED
   - 执行 Redis Lua 回补脚本
   - 记录回补日志（refund_type: 'TIMEOUT'）
3. 异常处理：Redis 或 DB 失败时记录日志，继续处理下一条

**超时时间配置**：
- 默认 15 分钟
- 可通过配置文件调整：`appointment.payment-timeout-minutes`

### 7.4 查询订单接口

**路径**：`GET /api/appointment/orders`

**请求参数**：
- `status`（可选）：订单状态（RESERVED/PAID/CANCELLED）
- `pageNum`：页码
- `pageSize`：每页大小

**业务逻辑**：
- 查询当前用户的挂号订单列表
- 按创建时间倒序
- 返回订单详情（包含医生、科室、时间、状态）

---

## 8. 异步通知与监控

### 8.1 Outbox 异步通知

**事件类型**：`appointment.paid`

**投递流程**：
1. 支付成功后写入 `t_outbox`（status=NEW）
2. `outbox-consumer` 轮询（每 1 秒）
3. 投递到 RabbitMQ（exchange: `appointment.events`, routingKey: `appointment.paid`）
4. 更新 Outbox 状态为 SENT

**RabbitMQ 配置**：
- Exchange: `appointment.events`（topic 类型）
- Queue: `appointment.notification.queue`
- Binding: `appointment.paid`

**消费端（appointment-service）**：
```java
@RabbitListener(queues = "appointment.notification.queue")
public void handlePaidEvent(String message) {
    AppointmentPaidEvent event = JSON.parseObject(message);
    
    // 幂等检查
    String key = "consumed:appointment.paid:" + event.getOrderNo();
    if (!idempotencyService.tryLock(key, Duration.ofHours(24))) {
        return;
    }
    
    // 模拟发送通知（打日志）
    log.info("【模拟短信通知】尊敬的用户，您已成功预约 {} {} {} 的号源",
        event.getAppointmentDate(), event.getTimeSlot(), event.getDoctorName());
    
    log.info("【模拟站内信】您的挂号订单 {} 已支付成功", event.getOrderNo());
}
```

### 8.2 监控指标

**业务指标（Micrometer + Prometheus）**：

```java
// 抢号成功率
Counter.builder("appointment.grab.success")
    .tag("dept", deptName)
    .register(meterRegistry);

Counter.builder("appointment.grab.failed")
    .tag("reason", failReason) // SOLD_OUT, ALREADY_GRABBED, RATE_LIMITED
    .register(meterRegistry);

// 支付成功率
Counter.builder("appointment.payment.success").register(meterRegistry);
Counter.builder("appointment.payment.timeout").register(meterRegistry);

// 取消订单统计
Counter.builder("appointment.cancel")
    .tag("type", cancelType) // USER_CANCEL, TIMEOUT
    .register(meterRegistry);

// 库存回补统计
Counter.builder("appointment.refund.success").register(meterRegistry);
Counter.builder("appointment.refund.failed").register(meterRegistry);

// 响应时间
Timer.builder("appointment.grab.duration").register(meterRegistry);
Timer.builder("appointment.payment.duration").register(meterRegistry);
```

**健康检查**：
```java
@Component
public class AppointmentHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // 检查 Redis 连接
        // 检查 MySQL 连接
        // 检查 Stream 消费延迟
        return Health.up()
            .withDetail("redis", "UP")
            .withDetail("mysql", "UP")
            .withDetail("streamLag", streamLag)
            .build();
    }
}
```

**日志规范**：
```
[APPOINTMENT] [GRAB] userId={}, scheduleId={}, result={}, reason={}
[APPOINTMENT] [PAYMENT] orderNo={}, userId={}, result={}
[APPOINTMENT] [CANCEL] orderNo={}, type={}, reason={}
[APPOINTMENT] [REFUND] scheduleId={}, orderNo={}, result={}
[APPOINTMENT] [STREAM] consumed={}, lag={}
```

### 8.3 异常告警

**关键指标告警**：
- 抢号失败率 > 50%（可能是库存预热失败）
- Stream 消费延迟 > 5 秒（可能是消费者挂了）
- 库存回补失败率 > 10%（Redis 异常）
- 支付超时率 > 30%（用户体验问题）

**告警方式**：
- Prometheus AlertManager
- 日志关键字监控

---


## 9. 测试验证与部署

### 9.1 测试脚本设计

**脚本文件**：`test-appointment.sh`

#### 9.1.1 场景 1：正常流程

```bash
#!/bin/bash

# 1. 注册并登录
curl -X POST http://localhost:8080/api/user/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"test_user","password":"123456","displayName":"测试用户"}'

TOKEN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"test_user","password":"123456"}' | jq -r '.data.token')

echo "Token: $TOKEN"

# 2. 查询可用排班
echo "=== 查询排班 ==="
curl -s http://localhost:8080/api/appointment/schedules?startDate=2026-05-27&endDate=2026-05-28 | jq

# 3. 抢号（记录 scheduleId）
SCHEDULE_ID=123456
echo "=== 抢号 ==="
curl -s -X POST http://localhost:8080/api/appointment/grab \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: test-req-001" \
  -H 'Content-Type: application/json' \
  -d "{\"scheduleId\": $SCHEDULE_ID}" | jq

# 4. 等待异步落库
echo "=== 等待异步落库 ==="
sleep 2

# 5. 查询订单列表
echo "=== 查询订单 ==="
ORDERS=$(curl -s http://localhost:8080/api/appointment/orders \
  -H "Authorization: Bearer $TOKEN" | jq)
echo "$ORDERS"

ORDER_NO=$(echo "$ORDERS" | jq -r '.data[0].orderNo')
echo "订单号: $ORDER_NO"

# 6. 支付
echo "=== 支付订单 ==="
curl -s -X POST "http://localhost:8080/api/appointment/pay/$ORDER_NO" \
  -H "Authorization: Bearer $TOKEN" | jq

# 7. 再次查询订单（状态应为 PAID）
echo "=== 查询订单状态 ==="
curl -s http://localhost:8080/api/appointment/orders \
  -H "Authorization: Bearer $TOKEN" | jq

# 8. 查看 RabbitMQ 消息
echo "=== 检查 RabbitMQ ==="
echo "访问 http://localhost:15672 查看 appointment.notification.queue"

# 9. 查看 appointment-service 日志
echo "=== 查看服务日志 ==="
echo "应该看到模拟通知日志"
```

#### 9.1.2 场景 2：重复抢号

```bash
# 使用相同的 scheduleId 和 userId 再次抢号
echo "=== 重复抢号测试 ==="
curl -s -X POST http://localhost:8080/api/appointment/grab \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: test-req-002" \
  -H 'Content-Type: application/json' \
  -d "{\"scheduleId\": $SCHEDULE_ID}" | jq

# 预期返回：ALREADY_GRABBED
```

#### 9.1.3 场景 3：库存耗尽

```bash
# 并发抢号，直到库存耗尽
echo "=== 库存耗尽测试 ==="
for i in {1..25}; do
  curl -s -X POST http://localhost:8080/api/appointment/grab \
    -H "Authorization: Bearer $TOKEN" \
    -H "Idempotency-Key: test-req-stock-$i" \
    -H 'Content-Type: application/json' \
    -d "{\"scheduleId\": $SCHEDULE_ID}" &
done
wait

# 预期：前 20 个成功，后 5 个返回 SOLD_OUT
```

#### 9.1.4 场景 4：用户主动取消

```bash
# 1. 抢号
echo "=== 抢号 ==="
curl -s -X POST http://localhost:8080/api/appointment/grab \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: test-req-cancel-001" \
  -H 'Content-Type: application/json' \
  -d "{\"scheduleId\": $SCHEDULE_ID}" | jq

# 2. 等待落库
sleep 2

# 3. 获取订单号
ORDER_NO=$(curl -s http://localhost:8080/api/appointment/orders \
  -H "Authorization: Bearer $TOKEN" | jq -r '.data[0].orderNo')

# 4. 取消订单
echo "=== 取消订单 ==="
curl -s -X POST "http://localhost:8080/api/appointment/cancel/$ORDER_NO" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"reason": "临时有事"}' | jq

# 5. 验证库存已回补
echo "=== 验证库存回补 ==="
redis-cli -p 36379 GET "appointment:stock:$SCHEDULE_ID"

# 6. 验证用户已从去重集合移除
USER_ID=$(curl -s http://localhost:8080/api/user/info \
  -H "Authorization: Bearer $TOKEN" | jq -r '.data.userId')
redis-cli -p 36379 SISMEMBER "appointment:buyers:$SCHEDULE_ID" "$USER_ID"
# 预期：返回 0
```

#### 9.1.5 场景 5：支付超时取消

```bash
# 1. 抢号但不支付
echo "=== 抢号但不支付 ==="
curl -s -X POST http://localhost:8080/api/appointment/grab \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: test-req-timeout-001" \
  -H 'Content-Type: application/json' \
  -d "{\"scheduleId\": $SCHEDULE_ID}" | jq

sleep 2

ORDER_NO=$(curl -s http://localhost:8080/api/appointment/orders \
  -H "Authorization: Bearer $TOKEN" | jq -r '.data[0].orderNo')

# 2. 手动修改订单的 reserved_at 时间（模拟超时）
mysql -uroot -proot order_db -e "
UPDATE t_appointment_order 
SET reserved_at = NOW() - INTERVAL 16 MINUTE 
WHERE order_no = '$ORDER_NO'"

# 3. 等待定时任务执行（或手动触发）
echo "=== 等待超时取消定时任务 ==="
sleep 60

# 4. 验证订单状态变为 CANCELLED
curl -s "http://localhost:8080/api/appointment/orders" \
  -H "Authorization: Bearer $TOKEN" | jq

# 5. 验证库存已回补
redis-cli -p 36379 GET "appointment:stock:$SCHEDULE_ID"
```

#### 9.1.6 场景 6：限流验证

```bash
# 快速连续请求，触发用户限流
echo "=== 限流测试 ==="
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/api/appointment/grab \
    -H "Authorization: Bearer $TOKEN" \
    -H "Idempotency-Key: test-req-ratelimit-$i" \
    -H 'Content-Type: application/json' \
    -d "{\"scheduleId\": $SCHEDULE_ID}"
  sleep 0.1
done

# 预期：前几个成功，后面返回 RATE_LIMIT_EXCEEDED (429)
```

### 9.2 启动与验证步骤

#### 9.2.1 启动顺序

```bash
# 1. 启动基础设施
docker compose up -d

# 2. 初始化数据库（包含挂号相关表和测试数据）
mysql -uroot -proot < sql/init-appointment.sql

# 3. 编译项目
mvn -T 1C clean package -DskipTests

# 4. 启动服务（在不同终端）
mvn -q -pl gateway spring-boot:run              # 终端1: 8080
mvn -q -pl user-service spring-boot:run         # 终端2: 8084
mvn -q -pl appointment-service spring-boot:run  # 终端3: 8089
mvn -q -pl outbox-consumer spring-boot:run      # 终端4: 8090

# 5. 验证服务注册
# 访问 Nacos: http://localhost:8848/nacos
# 应该看到 appointment-service 已注册

# 6. 执行测试脚本
chmod +x test-appointment.sh
./test-appointment.sh
```

#### 9.2.2 停止服务

```bash
# 停止挂号相关服务
lsof -ti :8080 :8084 :8089 :8090 | xargs kill

# 停止基础设施
docker compose down
```

### 9.3 配置文件

#### 9.3.1 appointment-service/application.yml

```yaml
server:
  port: 8089

spring:
  application:
    name: appointment-service
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
        namespace: public
  datasource:
    url: jdbc:mysql://localhost:3306/order_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  redis:
    host: localhost
    port: 36379
    database: 0
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

# 挂号业务配置
appointment:
  payment-timeout-minutes: 15
  stream-consumer-interval-ms: 50
  stock-preload-days: 7
  
# Snowflake ID 配置
snowflake:
  worker-id: 5
  datacenter-id: 1

# 监控配置
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    tags:
      application: ${spring.application.name}
    export:
      prometheus:
        enabled: true

# 日志配置
logging:
  level:
    com.lx.governance.appointment: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
```

#### 9.3.2 Gateway 路由配置更新

在 `gateway/src/main/resources/application.yml` 中新增路由：

```yaml
spring:
  cloud:
    gateway:
      routes:
        # 新增挂号服务路由
        - id: appointment-service
          uri: lb://appointment-service
          predicates:
            - Path=/api/appointment/**
          filters:
            - StripPrefix=0
```

### 9.4 文档更新

#### 9.4.1 README.md 新增章节

在 `README.md` 中新增：

```markdown
# 📅 Part 4: 医院挂号场景

## 系统概述

演示高并发挂号场景的完整治理方案，包含 10 个治理层次。

## 核心能力

- **双层限流**：批次热点（scheduleId）+ 单用户防刷（userId）
- **库存保护**：Redis Lua 原子扣减 + Stream 异步落库
- **幂等防重**：请求级 + 订单级双重保障
- **超时取消**：定时任务 + 库存回补
- **异步通知**：Outbox 模式 + RabbitMQ

## 快速开始

### 1. 启动基础设施

```bash
docker compose up -d
```

### 2. 初始化数据库

```bash
mysql -uroot -proot < sql/init-appointment.sql
```

### 3. 编译项目

```bash
mvn -T 1C clean package -DskipTests
```

### 4. 启动服务

```bash
# 在不同终端分别启动
mvn -q -pl gateway spring-boot:run              # 8080
mvn -q -pl user-service spring-boot:run         # 8084
mvn -q -pl appointment-service spring-boot:run  # 8089
mvn -q -pl outbox-consumer spring-boot:run      # 8090
```

### 5. 执行测试脚本

```bash
./test-appointment.sh
```

## 学习路径

| 步骤 | 主题 | 实验内容 | 观察重点 |
|------|------|----------|----------|
| 1 | 双层限流 | 并发抢号 | Gateway 限流生效 |
| 2 | 库存保护 | 超量抢号 | Redis Lua 防超卖 |
| 3 | 异步落库 | Stream 消费 | 削峰填谷效果 |
| 4 | 幂等防重 | 重复请求 | 幂等机制生效 |
| 5 | 超时取消 | 不支付订单 | 定时任务回补库存 |
| 6 | 主动取消 | 用户取消 | 库存回补幂等 |
| 7 | 异步通知 | 支付成功 | Outbox + MQ 解耦 |

## 核心技术模式

| 模式 | 实现 | 位置 |
|------|------|------|
| 双层限流 | RedisRateLimiter | AppointmentRateLimitFilter |
| 库存保护 | Redis Lua 脚本 | appointment_grab.lua |
| 异步落库 | Redis Stream | StreamConsumerService |
| 幂等防重 | Redis SET NX | IdempotencyService |
| 超时取消 | 定时任务 | TimeoutCancelScheduler |
| 库存回补 | Redis Lua 脚本 | appointment_refund.lua |
| 异步通知 | Outbox 模式 | AppointmentPaidEventHandler |

## 10 个治理层次详解

### 1. 放号瞬间入口限流

**实现**：Gateway 按 scheduleId 限流
- 热门科室：20 QPS，突发 40
- 普通科室：10 QPS，突发 20

**验证**：并发抢号，观察 429 响应

### 2. 单用户防刷

**实现**：Gateway 按 userId 限流
- 每秒 2 次，突发 5 次

**验证**：快速连续请求，触发限流

### 3. 抢号前的库存保护

**实现**：Redis Lua 原子扣减
- 检查库存 > 0
- 检查用户未抢过
- 扣减库存
- 记录用户
- 写入 Stream

**验证**：超量抢号，观察 SOLD_OUT

### 4. 抢到号源后的订单创建

**实现**：Stream 异步落库 + 幂等表
- 50ms 轮询消费
- DB 唯一约束兜底

**验证**：查看订单表，状态为 RESERVED

### 5. 支付前的号源占用

**实现**：订单状态 RESERVED，15 分钟有效期

**验证**：抢号后不支付，15 分钟后自动取消

### 6. 支付成功后的异步处理

**实现**：Outbox + RabbitMQ
- 支付成功写 Outbox
- outbox-consumer 投递 MQ
- appointment-service 消费（模拟通知）

**验证**：查看服务日志，看到模拟通知

### 7. 取消挂号/退款后的库存回补

**实现**：Redis Lua 回补 + 幂等日志
- INCR 库存
- SREM 用户
- 记录回补日志

**验证**：取消后查看 Redis 库存 +1

### 8. 热门科室和普通科室分开治理

**实现**：科室表 is_hot 字段
- 热门科室限流更严格
- 号源配置更少

**验证**：对比不同科室的限流效果

### 9. 数据库兜底保护

**实现**：Redis 优先，DB 兜底
- 查询排班先查 Redis
- Redis 无数据回退 DB

**验证**：清空 Redis，仍能查询排班

### 10. 失败补偿和人工兜底

**实现**：
- 回补日志表记录所有回补操作
- 幂等防止重复回补
- 异常日志便于人工介入

**验证**：查看 t_quota_refund_log 表
```

#### 9.4.2 CLAUDE.md 更新

在 `CLAUDE.md` 中新增：

```markdown
## Module Architecture (更新)

```
client → gateway(8080) → user-service(8084)
                       → order-service(8081) → inventory-service(8083)
                                             → payment-service(8082)
                                             → outbox-consumer(8090) → RabbitMQ
                       → appointment-service(8089) → Redis + MySQL
```

| Module | Role |
|---|---|
| `appointment-service` | 挂号服务：排班查询、抢号、支付、取消、库存回补 |

## Key Patterns (新增)

### 挂号场景（appointment-service）
- **双层限流** — Gateway 按 scheduleId + userId 两层限流
- **Redis Lua 库存保护** — 原子扣减 + 去重 + Stream 写入
- **Stream 异步落库** — 50ms 轮询消费，削峰填谷
- **超时取消** — 定时任务扫描 RESERVED 订单，15 分钟超时自动取消
- **库存回补** — Redis Lua 回补 + 幂等日志防重复
```

---

## 10. 实现清单

### 10.1 模块结构

```
appointment-service/
├── src/main/java/com/lx/governance/appointment/
│   ├── controller/
│   │   ├── AppointmentController.java          # 挂号相关接口
│   │   └── ScheduleController.java             # 排班查询接口
│   ├── service/
│   │   ├── AppointmentService.java             # 挂号核心业务
│   │   ├── ScheduleService.java                # 排班业务
│   │   ├── StreamConsumerService.java          # Stream 消费器
│   │   ├── TimeoutCancelScheduler.java         # 超时取消定时任务
│   │   ├── StockPreloadService.java            # 库存预热
│   │   └── AppointmentPaidEventHandler.java    # 支付成功事件处理
│   ├── domain/
│   │   ├── Schedule.java                       # 排班实体
│   │   ├── AppointmentOrder.java               # 挂号订单实体
│   │   ├── Department.java                     # 科室实体
│   │   ├── Doctor.java                         # 医生实体
│   │   ├── AppointmentIdempotency.java         # 幂等实体
│   │   └── QuotaRefundLog.java                 # 回补日志实体
│   ├── repo/
│   │   ├── ScheduleRepository.java
│   │   ├── AppointmentOrderRepository.java
│   │   ├── DepartmentRepository.java
│   │   ├── DoctorRepository.java
│   │   ├── AppointmentIdempotencyRepository.java
│   │   └── QuotaRefundLogRepository.java
│   ├── config/
│   │   ├── RedisConfig.java                    # Redis 配置
│   │   ├── RabbitMQConfig.java                 # RabbitMQ 配置
│   │   └── SchedulerConfig.java                # 定时任务配置
│   └── dto/
│       ├── GrabRequest.java
│       ├── GrabResultVO.java
│       ├── ScheduleVO.java
│       ├── OrderVO.java
│       └── CancelRequest.java
├── src/main/resources/
│   ├── application.yml
│   ├── lua/
│   │   ├── appointment_grab.lua                # 抢号脚本
│   │   └── appointment_refund.lua              # 回补脚本
│   └── mapper/
│       └── *.xml                                # MyBatis 映射文件
└── pom.xml

gateway/
└── src/main/java/com/lx/governance/gateway/
    └── filter/
        └── AppointmentRateLimitFilter.java     # 双层限流过滤器

sql/
└── init-appointment.sql                         # 数据库初始化脚本

test-appointment.sh                              # 测试脚本
```

### 10.2 核心类清单

**Controller 层**：
- `AppointmentController` - 抢号、支付、取消接口
- `ScheduleController` - 排班查询接口

**Service 层**：
- `AppointmentService` - 抢号、支付、取消核心逻辑
- `ScheduleService` - 排班查询、库存查询
- `StreamConsumerService` - Stream 消费器，异步落库
- `TimeoutCancelScheduler` - 超时取消定时任务
- `StockPreloadService` - 启动时库存预热
- `AppointmentPaidEventHandler` - RabbitMQ 消费者，处理支付成功事件

**Domain 层**：
- `Schedule` - 排班实体
- `AppointmentOrder` - 挂号订单实体
- `Department` - 科室实体
- `Doctor` - 医生实体
- `AppointmentIdempotency` - 幂等实体
- `QuotaRefundLog` - 回补日志实体

**Repository 层**：
- 各实体对应的 Repository 接口

**Config 层**：
- `RedisConfig` - Redis 配置，加载 Lua 脚本
- `RabbitMQConfig` - RabbitMQ 队列、交换机配置
- `SchedulerConfig` - 定时任务配置

**Gateway Filter**：
- `AppointmentRateLimitFilter` - 双层限流过滤器

**Lua 脚本**：
- `appointment_grab.lua` - 抢号脚本
- `appointment_refund.lua` - 回补脚本

**SQL 脚本**：
- `init-appointment.sql` - 建表 + 测试数据

**测试脚本**：
- `test-appointment.sh` - 完整测试场景

---

## 11. 总结

### 11.1 设计亮点

1. **完整的高并发治理方案**：覆盖从入口限流到最终一致性的 10 个层次
2. **复用现有基础设施**：Gateway、Redis、Outbox、RabbitMQ 全部复用
3. **不修改原有逻辑**：独立模块，与订单服务平行
4. **生产级实践**：双层限流、Lua 原子操作、Stream 削峰、幂等防重
5. **完整的测试验证**：6 个测试场景，覆盖正常和异常流程

### 11.2 技术价值

- ✅ 为什么需要双层限流（批次热点 + 单用户防刷）
- ✅ 为什么用 Redis Lua 而不是分布式锁（性能 + 原子性）
- ✅ 为什么用 Stream 异步落库（削峰填谷）
- ✅ 为什么需要幂等防重（请求级 + 订单级）
- ✅ 为什么需要超时取消（防止号源长期占用）
- ✅ 为什么需要库存回补幂等（防止重复回补）
- ✅ 为什么用 Outbox 而不是直接发 MQ（本地事务保障）
- ✅ 为什么热门科室要单独配置（差异化治理）

### 11.3 学习建议

1. **先理解流程**：从查询排班到支付成功的完整链路
2. **再看限流**：Gateway 双层限流如何保护下游
3. **重点看 Lua**：Redis Lua 如何保证原子性
4. **关注异步**：Stream 如何削峰，Outbox 如何解耦
5. **理解幂等**：请求级、订单级、回补级三层幂等
6. **观察监控**：Prometheus 指标如何反映业务状态

### 11.4 后续扩展

**可选增强**：
- 增加医生停诊场景（批量取消订单 + 回补库存）
- 增加退款场景（已支付订单退款）
- 增加号源动态调整（医生临时加号）
- 增加黄牛检测（异常行为识别）
- 增加就诊提醒（定时任务 + 短信通知）

**生产化改造**：
- 限流参数动态配置（Nacos 配置中心）
- 库存预热优化（按时间段预热）
- Stream 消费优化（多消费者并行）
- 监控告警完善（Grafana 大盘）
- 压测验证（JMeter 压测脚本）

---

**设计完成日期**：2026-05-26

**设计状态**：已批准，待实现

