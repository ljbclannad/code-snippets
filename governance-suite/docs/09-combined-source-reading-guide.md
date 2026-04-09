# Spring 与中间件源码联合解读入口

这份文档是把 [源码延伸入口](/Users/lejinbo/LLM/code-snippets/governance-suite/docs/07-source-reading-guide.md) 和 [Spring 源码解读入口](/Users/lejinbo/LLM/code-snippets/governance-suite/docs/08-spring-source-reading-guide.md) 合并后的阅读路线。

它不是官方源码文档，也不是完整源码解析，而是基于本项目给出的“联合解读入口”：

- 先看本项目里这一能力是怎么接入的
- 再看 Spring 负责装配、代理、分发、调度的部分
- 最后看中间件自己真正执行业务能力的部分

这份 `09` 的目标不是把类列得更多，而是把阅读顺序排清楚。

## 1. 服务注册与发现

### 先看项目里的入口

- [application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/resources/application.yml)
- [InventoryClient.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/client/InventoryClient.java)
- [PaymentClient.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/client/PaymentClient.java)
- [NacosRegistrationRetryRunner.java](/Users/lejinbo/LLM/code-snippets/governance-suite/common/src/main/java/com/lx/governance/common/cloud/NacosRegistrationRetryRunner.java)

### 先看 Spring 哪一层

- `org.springframework.cloud.openfeign.FeignClientsRegistrar`
- `org.springframework.cloud.openfeign.FeignClientFactoryBean`
- `org.springframework.cloud.loadbalancer.blocking.client.BlockingLoadBalancerClient`
- `org.springframework.context.event.EventListenerMethodProcessor`
- `org.springframework.boot.context.event.ApplicationReadyEvent`

### 再看中间件哪一层

- `com.alibaba.cloud.nacos.registry.NacosAutoServiceRegistration`
- `com.alibaba.cloud.nacos.registry.NacosServiceRegistry`
- `com.alibaba.cloud.nacos.discovery.NacosDiscoveryClient`
- `com.alibaba.cloud.nacos.discovery.reactive.NacosReactiveDiscoveryClient`

### 这条链你要理解什么

1. `@FeignClient` 先被 Spring 注册成代理 Bean
2. 没有固定 URL 时，请求交给 LoadBalancer
3. LoadBalancer 再去拿 DiscoveryClient 的实例列表
4. Nacos 负责实例注册和发现
5. `@EventListener(ApplicationReadyEvent.class)` 则解释了为什么注册重试会在应用 ready 后触发

## 2. 网关路由、WebFlux 与 Sentinel Gateway

### 先看项目里的入口

- [application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/resources/application.yml)
- [TraceIdGlobalFilter.java](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/java/com/lx/governance/gateway/filter/TraceIdGlobalFilter.java)
- [SentinelGatewayConfig.java](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/java/com/lx/governance/gateway/config/SentinelGatewayConfig.java)

### 先看 Spring 哪一层

- `org.springframework.web.reactive.DispatcherHandler`
- `org.springframework.web.server.adapter.HttpWebHandlerAdapter`
- `org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping`
- `org.springframework.cloud.gateway.handler.FilteringWebHandler`
- `org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory`
- `org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory`

### 再看中间件哪一层

- `com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter`
- `com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager`
- `com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager`

### 这条链你要理解什么

1. Gateway 首先是 WebFlux 应用
2. Spring Cloud Gateway 负责路由匹配和过滤器链
3. Sentinel Gateway Filter 再插入到这条链里做限流和拦截

## 3. Spring MVC、异常处理与 Trace 透传

### 先看项目里的入口

- [OrderController.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/controller/OrderController.java)
- [GlobalExceptionHandler.java](/Users/lejinbo/LLM/code-snippets/governance-suite/common/src/main/java/com/lx/governance/common/web/GlobalExceptionHandler.java)
- [TraceIdFilter.java](/Users/lejinbo/LLM/code-snippets/governance-suite/common/src/main/java/com/lx/governance/common/web/TraceIdFilter.java)

### 先看 Spring 哪一层

- `org.springframework.web.servlet.DispatcherServlet`
- `org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping`
- `org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter`
- `org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver`
- `org.springframework.web.filter.OncePerRequestFilter`

### 再看中间件哪一层

这里基本没有额外的中间件核心源码，主要就是 Spring Web 自己在起作用。

### 这条链你要理解什么

1. Filter 先处理请求和响应头
2. DispatcherServlet 找到 Controller 方法
3. 参数绑定、返回值序列化、异常统一处理都在 Spring MVC 里完成

## 4. AOP、Spring 事务与 Seata 全局事务

### 先看项目里的入口

- [OrderAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/OrderAppService.java)
- [InventoryAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/inventory-service/src/main/java/com/lx/governance/inventory/service/InventoryAppService.java)
- [SeckillOrderStreamConsumer.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/SeckillOrderStreamConsumer.java)

### 先看 Spring 哪一层

- `org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator`
- `org.springframework.aop.framework.CglibAopProxy`
- `org.springframework.aop.framework.JdkDynamicAopProxy`
- `org.springframework.aop.framework.ReflectiveMethodInvocation`
- `org.springframework.transaction.interceptor.TransactionInterceptor`
- `org.springframework.transaction.interceptor.TransactionAspectSupport`
- `org.springframework.transaction.support.TransactionTemplate`
- `org.springframework.jdbc.datasource.DataSourceTransactionManager`

### 再看中间件哪一层

- `io.seata.spring.annotation.GlobalTransactionalInterceptor`
- `io.seata.tm.api.TransactionalTemplate`
- `io.seata.rm.datasource.DataSourceProxy`
- `io.seata.rm.datasource.exec.ExecuteTemplate`
- `io.seata.rm.datasource.undo.UndoLogManager`

### 这条链你要理解什么

1. 先理解 Spring AOP 怎么把方法包装成代理调用
2. 再理解 Spring 本地事务如何拦截方法并开启事务
3. 最后再看 Seata 如何在 Spring 代理链上叠加全局事务能力

如果不先读 Spring 的代理和事务拦截器，直接看 Seata 会很容易乱。

## 5. `@SentinelResource` 与方法级保护

### 先看项目里的入口

- [InventoryAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/inventory-service/src/main/java/com/lx/governance/inventory/service/InventoryAppService.java)
- [PaymentAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/payment-service/src/main/java/com/lx/governance/payment/service/PaymentAppService.java)

### 先看 Spring 哪一层

- `org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator`
- `org.springframework.aop.framework.ReflectiveMethodInvocation`

### 再看中间件哪一层

- `com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect`
- `com.alibaba.csp.sentinel.Entry`
- `com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager`

### 这条链你要理解什么

1. 方法先进入 Spring 代理链
2. Sentinel 切面在代理链里接管调用
3. 再根据规则决定正常放行、blockHandler 或 fallback

## 6. Outbox、定时任务与 RabbitMQ 客户端

### 先看项目里的入口

- [OutboxPublisher.java](/Users/lejinbo/LLM/code-snippets/governance-suite/outbox-consumer/src/main/java/com/lx/governance/outbox/service/OutboxPublisher.java)
- [OutboxRepo.java](/Users/lejinbo/LLM/code-snippets/governance-suite/outbox-consumer/src/main/java/com/lx/governance/outbox/repo/OutboxRepo.java)
- [PaymentEventListener.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/PaymentEventListener.java)

### 先看 Spring 哪一层

- `org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor`
- `org.springframework.scheduling.config.ScheduledTaskRegistrar`
- `org.springframework.amqp.rabbit.core.RabbitTemplate`
- `org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer`

### 再看中间件哪一层

RabbitMQ 这里主要通过 Spring AMQP 使用客户端，真正更底层的 Broker 行为不在本项目里展开。

### 这条链你要理解什么

1. `@Scheduled` 让 Spring 周期性执行轮询任务
2. 业务代码自己实现 Outbox 模式
3. Spring AMQP 帮你完成消息发送和消费容器管理

## 7. Redis 幂等、Lua 与 Stream

### 先看项目里的入口

- [IdempotencyService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/IdempotencyService.java)
- [OrderAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/OrderAppService.java)
- [SeckillOrderStreamConsumer.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/SeckillOrderStreamConsumer.java)

### 先看 Spring 哪一层

- `org.springframework.data.redis.core.StringRedisTemplate`
- `org.springframework.data.redis.core.script.DefaultRedisScript`
- `org.springframework.data.redis.core.StreamOperations`

### 再看中间件哪一层

Redis 服务端 Lua 执行和 Stream 内核不在这个项目里，真正深入时要去 Redis 源码。

### 这条链你要理解什么

1. Spring Data Redis 只是 Java 侧封装
2. Lua 和 Stream 的原子性、消费模型最终还是 Redis 服务端在保证

## 8. 配置绑定、条件装配与功能开关

### 先看项目里的入口

- [ThreadPoolProperties.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/config/ThreadPoolProperties.java)
- [application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/payment-service/src/main/resources/application.yml)
- [NacosRegistrationRetryRunner.java](/Users/lejinbo/LLM/code-snippets/governance-suite/common/src/main/java/com/lx/governance/common/cloud/NacosRegistrationRetryRunner.java)

### 先看 Spring 哪一层

- `org.springframework.boot.context.properties.bind.Binder`
- `org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor`
- `org.springframework.boot.autoconfigure.condition.OnPropertyCondition`
- `org.springframework.context.annotation.ConfigurationClassPostProcessor`

### 再看中间件哪一层

这里通常还没有进入具体中间件实现，更多是 Spring Boot 如何决定“要不要装配这部分能力”。

### 这条链你要理解什么

1. 配置文件先被 Spring Boot 绑定成对象或条件
2. 条件满足后，对应功能 Bean 才会生效
3. 很多中间件自动配置都是建立在这套机制上

## 9. 推荐阅读顺序

如果你从零开始，我建议按这条顺序读：

1. Spring 容器启动与自动配置
2. Spring MVC / WebFlux 请求链
3. Spring AOP 与事务
4. Nacos 注册发现
5. Gateway 与 Sentinel
6. Seata 全局事务
7. Outbox 与 RabbitMQ
8. Redis Lua / Stream

原因很简单：

- 先不理解 Spring，就看不懂这些中间件是怎么被装进来的
- 先不理解代理和事务，就看不懂 Seata 和 Sentinel
- 先不理解请求链，就看不懂 Gateway 和 Feign

## 10. 怎么使用 07、08、09

- `07`：只看中间件源码入口
- `08`：只看 Spring 机制入口
- `09`：按联合链路一起读

如果你只保留一份主文档，优先读 `09`。
