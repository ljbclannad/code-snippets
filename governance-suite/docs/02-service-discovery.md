# 服务注册与发现

这一部分要回答的问题是：`gateway` 和 `order-service` 为什么可以按服务名调用别的服务，而不是写死地址。

## 关键组件

- Nacos：保存服务实例列表
- Spring Cloud Gateway：按 `lb://service-name` 路由
- OpenFeign + Spring Cloud LoadBalancer：按服务名选择实例

## 项目中的关键位置

- `gateway/src/main/resources/application.yml`
- `order-service/src/main/java/com/lx/governance/order/client/InventoryClient.java`
- `order-service/src/main/java/com/lx/governance/order/client/PaymentClient.java`
- `common/src/main/java/com/lx/governance/common/cloud/NacosRegistrationRetryRunner.java`

## 你应该观察什么

1. 服务启动后，Nacos 控制台里能看到 `gateway`、`order-service`、`payment-service`、`inventory-service`
2. `gateway` 的路由 URI 使用的是 `lb://...`
3. `order-service` 日志里会提示 Feign 未提供固定 URL，将通过 load-balancing 选择实例

## 常见学习误区

- 注册中心解决的是“怎么找到服务”，不是“怎么保证事务一致”
- 服务发现成功，不代表下游接口一定可用
- 本地单机能跑通，不代表生产环境网络、实例变更、心跳恢复就没有问题

## 建议实验

- 正常启动 4 个注册到 Nacos 的服务，观察实例列表
- 停掉 `inventory-service`，再发起下单，观察 Feign fallback 和错误传播
- 对比 `lb://service-name` 与固定 `http://host:port` 两种调用方式的差别
