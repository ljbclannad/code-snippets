# Lab 03: Outbox 重试与最终一致性

## 目标

理解 Outbox 如何把“本地事务成功”和“异步消息最终投递”拆开。

## 实验

启动 `outbox-consumer` 时增加环境变量：

```bash
LAB_FAIL_OUTBOX_PUBLISH=true
```

触发一条会写 `t_outbox` 的链路，例如支付回调。

## 预期现象

- `t_outbox.status` 从 `NEW` 变成 `FAILED`
- `retry_count` 增加
- `next_retry_at` 有值

关闭故障注入后重启 `outbox-consumer`，记录应继续重试并最终变为 `SENT`。

## 你要思考的问题

- 为什么这里不追求强一致？
- 为什么 `FAILED` 和 `DEAD` 都需要保留？
