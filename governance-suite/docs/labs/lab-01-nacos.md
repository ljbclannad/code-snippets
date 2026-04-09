# Lab 01: 服务注册与发现

## 目标

确认服务如何通过 Nacos 注册到注册中心，并由网关和 Feign 按服务名发现。

## 步骤

1. 启动基础设施：`docker compose up -d`
2. 启动 `gateway`、`user-service`、`inventory-service`、`payment-service`、`order-service`
3. 打开 Nacos 控制台 `http://localhost:8848/nacos`
4. 检查实例是否注册成功

## 预期现象

- Nacos 中能看到 5 个服务实例
- `gateway` 路由指向 `lb://...`
- `order-service` 可按服务名调用库存和支付

## 你要思考的问题

- 如果 Nacos 不可用，项目还能不能靠服务名调用？
- 为什么本地直连能跑，但它不适合作为服务治理学习的默认模式？
