# 分布式事务

这一部分关注的是：订单创建、库存扣减、支付准备是三个服务，为什么还能在失败时一起回滚。

## 当前实现

- `order-service` 的 `create()` 使用 `@GlobalTransactional`
- `inventory-service` 和 `payment-service` 都启用了 Seata AT
- 数据层仍然是本地数据库事务，Seata 负责跨服务协调

## 主链路

1. `order-service` 先写订单
2. 调 `inventory-service` 扣库存
3. 调 `payment-service` 创建支付单
4. 任一步失败，Seata 回滚全局事务

## 关键观察点

- `order-service` 中的 `OrderAppService.create`
- `inventory-service` 的库存乐观锁更新
- `payment-service` 的支付准备逻辑
- MySQL 中订单、库存、支付表在失败场景下是否一致

## 你应该理解的边界

- Seata AT 更适合数据库事务主导的场景
- Seata 不能替代消息一致性方案
- Seata 成本包括代理数据源、undo_log、回滚性能和兼容性验证

## 建议实验

- 打开 `LAB_FAIL_INVENTORY_DEDUCT=true`，发起下单，观察订单和支付是否回滚
- 打开 `LAB_FAIL_PAYMENT_PREPARE=true`，发起下单，观察库存是否回滚
- 对照没有异常时的正常下单结果，理解“全局成功”和“全局回滚”的区别
