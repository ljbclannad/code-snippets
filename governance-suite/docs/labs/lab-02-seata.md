# Lab 02: Seata 全局事务回滚

## 目标

理解 Seata AT 在下单链路里如何协调库存和支付准备。

## 实验 A：库存扣减失败

启动 `inventory-service` 时增加环境变量：

```bash
LAB_FAIL_INVENTORY_DEDUCT=true
```

然后发起下单请求。

## 预期现象

- 下单接口失败
- 订单记录不应保留为成功结果
- 支付准备不应保留成功结果
- 如果支付表已产生记录，也应在全局事务回滚后回到一致状态

## 实验 B：支付准备失败

启动 `payment-service` 时增加环境变量：

```bash
LAB_FAIL_PAYMENT_PREPARE=true
```

然后再次发起下单请求。

## 你要观察

- `order_db.t_order_*`
- `inventory_db.t_stock`
- `payment_db.t_payment_*`

## 你要思考的问题

- 为什么同步链路里更适合用 Seata，而不是只靠 MQ 补偿？
- Seata 已经在用了，为什么项目里仍然保留 Outbox？
