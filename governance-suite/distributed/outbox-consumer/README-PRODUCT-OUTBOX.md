# 商品事件投递

商品发布器默认关闭。初始化 `product_db.t_product_outbox` 后，在启动 `outbox-consumer` 时设置 `PRODUCT_OUTBOX_ENABLED=true`，即可在原有订单、支付发布器之外轮询商品事件。数据库连接沿用 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_USER`、`MYSQL_PASS`，库名为 `product_db`；也可通过 Spring 的 `product-outbox.datasource.*` 属性单独覆盖。

商品维护服务在业务事务中写入 Outbox；本模块只投递 JSON 原文，不修改商品状态或调用业务服务。事件结构为 `eventId`、`eventType: product.changed`、`product`（商品快照，包含版本）。

启用后声明持久化拓扑：

- 主交换机 `biz.events`（topic），路由键 `product.changed`，队列 `q.product.search`。
- 消费失败死信交换机 `biz.product.search.dead`（direct），路由键 `product.changed`，队列 `q.product.search.dead`。

搜索服务暂未启动时，主队列仍可积压消息。发布器发送持久消息，独立 RabbitTemplate 使用 `mandatory=true`；收到 broker ack 且没有 returned message 才标记 `SENT`。共享连接启用 correlated confirms 与 publisher returns，旧订单、支付发布逻辑不变。

投递失败时仍为 `NEW`，更新 `retry_count`、`error_reason` 和 `next_retry_at`，达到 `outbox.max-retries`（默认 5）后置为 `DEAD`。轮询间隔沿用 `outbox.poll-interval-ms`（默认 1000），重试间隔沿用 `outbox.retry-delay-seconds`（默认 10）。数据库 `DEAD` 表示事件未成功发布，与 RabbitMQ 消费失败死信队列是两个独立的故障阶段；人工排查后才应重新驱动失败事件。

这是至少一次投递：broker 已接收但数据库标记失败、超时或多个发布实例并发读取时，事件可能重复。搜索消费者必须按商品版本幂等处理，不依赖事件严格有序。初版不提供商品 Outbox 自动清理或管理重放接口。

## 验证

```bash
mvn -f distributed/outbox-consumer/pom.xml test
```

商品发布测试使用 H2 与 RabbitMQ SDK 边界模拟，覆盖成功事件不再次轮询发送、无路由时即便 ack 也按同一事件重试、重试间隔和预算耗尽，以及默认关闭和持久化队列配置。测试不连接真实 MySQL 或 RabbitMQ；真实 broker 确认、服务停机积压与恢复仍需端到端验证。
