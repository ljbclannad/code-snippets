# 限流、降级与保护

这部分的核心问题不是“如何让请求一定成功”，而是“系统在压力或异常下如何自保”。

## 当前项目里的保护手段

- Gateway RedisRateLimiter：按 IP 做粗粒度限流
- Sentinel Gateway：统一拦截和返回限流结果
- `@SentinelResource`：对库存和支付接口做 block/fallback 演示
- Feign fallback：下游不可用时做有限降级

## 学习重点

- 限流不是修复错误，而是保护系统
- fallback 不是业务成功，只是兜底结果
- 不同层次的限流粒度不同：网关、服务、热点参数

## 建议实验

- 对网关同一路径连续压测，观察 `429`
- 停掉某个下游服务，观察 `order-service` 的错误传播
- 对比网关层限流和服务层 fallback 的触发位置
