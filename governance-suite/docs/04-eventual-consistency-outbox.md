# 最终一致性与 Outbox

这一部分关注的是：为什么项目既用了 Seata，又保留了 Outbox。

答案是两者解决的问题不同：

- Seata：处理同步调用链路内的跨服务事务
- Outbox：处理“本地事务 + 异步消息投递”的一致性

## 当前实现

1. `order-service` 在本地事务里写订单和 `t_outbox`
2. `outbox-consumer` 轮询 `t_outbox`
3. 成功后把事件发到 RabbitMQ
4. `order-service` 消费支付成功事件后更新订单状态

## 状态流转

- `NEW`：待发送
- `FAILED`：发送失败，等待重试
- `SENT`：已成功发送
- `DEAD`：超过最大重试次数

## 关键学习点

- 为什么不能直接在本地事务里“顺手发 MQ”
- 为什么 Outbox 常见于最终一致性，而不是强一致事务
- 为什么重试、死信、人工介入点必须设计清楚

## 建议实验

- 打开 `LAB_FAIL_OUTBOX_PUBLISH=true`
- 触发支付回调或支付成功事件
- 查询 `t_outbox`，观察 `NEW -> FAILED -> DEAD` 或恢复后 `FAILED -> SENT`

## 观察 SQL

```sql
SELECT id, event_type, status, retry_count, next_retry_at, sent_at, error_reason
FROM t_outbox
ORDER BY id DESC
LIMIT 20;
```
