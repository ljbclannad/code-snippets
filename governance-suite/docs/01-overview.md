# 项目定位

`governance-suite` 现在的目标不是“做一个业务系统”，而是做一个可重复实验的分布式学习项目。

这个项目覆盖 6 条主线：

1. 服务注册与发现：Nacos + Gateway + OpenFeign
2. 分布式事务：Seata AT
3. 最终一致性：Outbox + RabbitMQ
4. 幂等与防重：Redis Key + 数据库唯一约束
5. 秒杀削峰：Redis Lua + Redis Stream
6. 限流与降级：Sentinel + Gateway RedisRateLimiter

## 建议学习顺序

1. 先跑通最简单的下单链路，理解模块职责。
2. 再看 Nacos 注册发现和 Feign 调用关系。
3. 然后看 Seata AT 如何保证下单、扣库存、支付准备的回滚。
4. 再看 Outbox 如何把“本地事务”和“消息发送”解耦。
5. 最后再看幂等、秒杀、限流这些高频治理细节。

## 模块职责

- `gateway`：统一入口、路由、网关限流、Sentinel 网关拦截
- `order-service`：发起下单事务、写订单、写 Outbox、消费支付成功事件
- `inventory-service`：扣库存，演示库存侧幂等和乐观锁
- `payment-service`：准备支付、确认支付并发送支付成功事件
- `outbox-consumer`：扫描 `t_outbox` 并可靠投递到 RabbitMQ
- `common`：统一返回、TraceId、异常处理、Nacos 注册重试

## 这不是生产方案大全

项目有意保留了“边界感”，用于学习而不是伪装成生产模板：

- Seata AT 不适合所有跨服务场景。
- Outbox 解决的是最终一致性，不是强一致。
- Redis Lua + Stream 增加了吞吐和异步能力，也增加了恢复复杂度。
- Sentinel 和限流是保护系统，不是修复业务错误。

## 学习方式

建议把每个知识点都按 4 个问题来理解：

1. 它解决什么问题？
2. 不用它会发生什么？
3. 它引入了什么成本？
4. 在这个项目里如何观察到它的行为？
