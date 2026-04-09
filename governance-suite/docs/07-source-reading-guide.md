# 源码延伸入口

这份文档不是组件官方源码文档，而是对“从本项目出发，如何进入上游分布式组件源码”的解读入口。

换句话说，这里列出的类和路径，是为了帮助你建立阅读顺序和定位思路：

1. 先看本项目里组件是怎么接入的
2. 再沿着注解、配置、接口调用进入 Spring Cloud / Alibaba / Seata / Sentinel 的源码
3. 最后再去看组件内部更深的实现

如果你的目标是“理解业务如何使用组件”，这个项目本身已经够用。
如果你的目标是“理解组件内部机制”，这份文档就是源码延伸入口。

## 1. Nacos 注册与发现

### 先看项目里的接入点

- [application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/resources/application.yml)
- [InventoryClient.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/client/InventoryClient.java)
- [PaymentClient.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/client/PaymentClient.java)
- [NacosRegistrationRetryRunner.java](/Users/lejinbo/LLM/code-snippets/governance-suite/common/src/main/java/com/lx/governance/common/cloud/NacosRegistrationRetryRunner.java)

### 这部分源码解读要回答什么

- 服务实例是怎么注册到 Nacos 的
- `lb://service-name` 为什么能被解析成实例列表
- Feign 在没有固定 URL 时，为什么会走服务发现和负载均衡

### 建议进入的上游源码入口

- `org.springframework.cloud.client.serviceregistry.AbstractAutoServiceRegistration`
- `com.alibaba.cloud.nacos.registry.NacosAutoServiceRegistration`
- `com.alibaba.cloud.nacos.registry.NacosServiceRegistry`
- `com.alibaba.cloud.nacos.discovery.NacosDiscoveryClient`
- `com.alibaba.cloud.nacos.discovery.reactive.NacosReactiveDiscoveryClient`
- `org.springframework.cloud.loadbalancer.blocking.client.BlockingLoadBalancerClient`
- `org.springframework.cloud.openfeign.FeignClientFactoryBean`

### 建议阅读顺序

1. 从 `@FeignClient` 进入 `FeignClientFactoryBean`
2. 看没有 `url` 时，如何转到 LoadBalancer
3. 再看 LoadBalancer 如何向 DiscoveryClient 拿实例
4. 最后看 Nacos 注册类如何把本地实例写入注册中心

## 2. Seata AT 分布式事务

### 先看项目里的接入点

- [OrderAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/OrderAppService.java)
- [application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/resources/application.yml)
- [application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/inventory-service/src/main/resources/application.yml)
- [application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/payment-service/src/main/resources/application.yml)

### 这部分源码解读要回答什么

- `@GlobalTransactional` 为什么能把多个服务调用串成一个全局事务
- Seata AT 怎么代理数据源、记录回滚日志、执行补偿回滚
- 为什么同步链路失败时，订单、库存、支付准备能一起回滚

### 建议进入的上游源码入口

- `io.seata.spring.annotation.GlobalTransactional`
- `io.seata.spring.annotation.GlobalTransactionalInterceptor`
- `io.seata.tm.api.TransactionalTemplate`
- `io.seata.rm.datasource.DataSourceProxy`
- `io.seata.rm.datasource.exec.ExecuteTemplate`
- `io.seata.rm.datasource.undo.UndoLogManager`

### 建议阅读顺序

1. 从 `@GlobalTransactional` 进入拦截器
2. 看全局事务开启、提交、回滚的模板流程
3. 再看 AT 模式下数据源代理和 SQL 执行
4. 最后看 `undo_log` 如何支持回滚

## 3. Outbox 与最终一致性

### 先看项目里的接入点

- [OrderAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/OrderAppService.java)
- [OutboxRepo.java](/Users/lejinbo/LLM/code-snippets/governance-suite/outbox-consumer/src/main/java/com/lx/governance/outbox/repo/OutboxRepo.java)
- [OutboxPublisher.java](/Users/lejinbo/LLM/code-snippets/governance-suite/outbox-consumer/src/main/java/com/lx/governance/outbox/service/OutboxPublisher.java)

### 这部分源码解读要回答什么

- 为什么这里没有直接依赖某个“Outbox 框架”
- 为什么 Outbox 更像一种模式，而不是一个统一组件
- Spring 定时任务和 RabbitTemplate 在这个模式里分别扮演什么角色

### 建议进入的上游源码入口

- `org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor`
- `org.springframework.amqp.rabbit.core.RabbitTemplate`
- `org.springframework.amqp.rabbit.connection.CachingConnectionFactory`
- `org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer`

### 说明

Outbox 没有单独一个“官方源码核心入口”，因为这里主要是业务模式设计。真正值得看的源码是：

- Spring 如何调度轮询任务
- RabbitTemplate 如何发送消息
- 消费端容器如何接收并确认消息

## 4. Sentinel 限流与降级

### 先看项目里的接入点

- [SentinelGatewayConfig.java](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/java/com/lx/governance/gateway/config/SentinelGatewayConfig.java)
- [InventoryAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/inventory-service/src/main/java/com/lx/governance/inventory/service/InventoryAppService.java)
- [PaymentAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/payment-service/src/main/java/com/lx/governance/payment/service/PaymentAppService.java)

### 这部分源码解读要回答什么

- `@SentinelResource` 为什么能接管方法调用
- Gateway 规则为什么能在网关层直接拦截
- blockHandler 和 fallback 的执行入口在哪里

### 建议进入的上游源码入口

- `com.alibaba.csp.sentinel.annotation.SentinelResource`
- `com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect`
- `com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter`
- `com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager`
- `com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager`
- `com.alibaba.csp.sentinel.Entry`

### 建议阅读顺序

1. 从 `@SentinelResource` 进入切面
2. 看资源进入、异常捕获、blockHandler/fallback 的分发
3. 再看 GatewayFilter 如何把请求接入 Sentinel 槽链

## 5. Spring Cloud Gateway 路由与过滤

### 先看项目里的接入点

- [application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/resources/application.yml)
- [SentinelGatewayConfig.java](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/java/com/lx/governance/gateway/config/SentinelGatewayConfig.java)

### 这部分源码解读要回答什么

- `Path=/api/order/**` 这样的规则如何被解析成路由
- `StripPrefix=2` 为什么会在转发前生效
- 默认过滤器和全局过滤器如何参与请求链

### 建议进入的上游源码入口

- `org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator`
- `org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping`
- `org.springframework.cloud.gateway.handler.FilteringWebHandler`
- `org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory`
- `org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory`

## 6. Redis 幂等、Lua 与 Stream

### 先看项目里的接入点

- [IdempotencyService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/IdempotencyService.java)
- [OrderAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/OrderAppService.java)
- [SeckillOrderStreamConsumer.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/SeckillOrderStreamConsumer.java)

### 这部分源码解读要回答什么

- Spring Data Redis 如何执行 Lua 脚本
- Redis Stream 消费在 Spring 里是怎么封装的
- 为什么项目里选择了“Redis 原子预扣 + 异步落库”

### 建议进入的上游源码入口

- `org.springframework.data.redis.core.StringRedisTemplate`
- `org.springframework.data.redis.core.script.DefaultRedisScript`
- `org.springframework.data.redis.connection.stream`
- `org.springframework.data.redis.core.StreamOperations`

### 说明

如果你真正想深入 Redis Stream 和 Lua 的内部实现，最终还是要回到 Redis 服务端源码；这个项目只能帮助你理解 Java 客户端侧是怎么接入的。

## 7. 怎么用这份源码延伸文档

建议固定按这个顺序读：

1. 先看本项目中的接入代码
2. 再跳到这里给出的上游类入口
3. 看一遍主调用链
4. 回到项目里验证“这个组件到底帮业务做了什么”

这样读，才不会一上来就淹没在框架源码里。
