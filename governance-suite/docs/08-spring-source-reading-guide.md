# Spring 源码解读入口

这份文档同样不是 Spring 官方文档，而是基于本项目的接入代码，对 Spring Framework / Spring Boot / Spring Cloud 的源码阅读入口做的解读。

这里关注的是“Spring 本身怎么把这些能力串起来”，不是 Nacos、Seata、Sentinel 这些组件自己的内部实现。你可以把它看成：

- 组件源码延伸：看中间件自己怎么工作
- Spring 源码解读：看 Spring 怎么装配、代理、调度、分发这些能力

## 1. 容器启动与自动配置

### 先看项目里的入口

- 各服务的 `*Application.java`
- [application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/resources/application.yml)
- [application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/resources/application.yml)

### 这部分源码解读要回答什么

- `SpringApplication.run(...)` 到底做了什么
- 为什么只靠依赖和 `application.yml`，很多 Bean 就能自动生效
- 自动配置为什么能按类路径和属性条件决定是否装配

### 建议进入的源码入口

- `org.springframework.boot.SpringApplication`
- `org.springframework.boot.autoconfigure.AutoConfigurationImportSelector`
- `org.springframework.context.support.AbstractApplicationContext`
- `org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory`
- `org.springframework.beans.factory.support.DefaultListableBeanFactory`

### 建议阅读顺序

1. 从 `SpringApplication.run` 开始
2. 看 `refresh()` 期间 BeanFactory 如何创建 Bean
3. 再看自动配置选择器如何导入自动配置类
4. 最后回到项目里理解为什么配个依赖就能启用对应能力

## 2. Bean 生命周期与依赖注入

### 先看项目里的入口

- [ThreadPoolProperties.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/config/ThreadPoolProperties.java)
- [NacosRegistrationRetryRunner.java](/Users/lejinbo/LLM/code-snippets/governance-suite/common/src/main/java/com/lx/governance/common/cloud/NacosRegistrationRetryRunner.java)
- 各个 `@Component`、`@Service`、`@Configuration`

### 这部分源码解读要回答什么

- `@Component` 是怎么被扫描成 Bean 的
- 构造器注入是怎么完成的
- `@ConfigurationProperties` 为什么能把配置绑定成对象

### 建议进入的源码入口

- `org.springframework.context.annotation.ClassPathBeanDefinitionScanner`
- `org.springframework.context.annotation.ConfigurationClassPostProcessor`
- `org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor`
- `org.springframework.boot.context.properties.bind.Binder`
- `org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor`

### 建议阅读顺序

1. 先看类扫描和 BeanDefinition 注册
2. 再看 Bean 实例化和依赖注入
3. 最后看配置绑定如何把 `application.yml` 映射到 Java 对象

## 3. Spring MVC 请求链

这部分对应 `order-service`、`inventory-service`、`payment-service`、`outbox-consumer` 这些 Servlet 应用。

### 先看项目里的入口

- [OrderController.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/controller/OrderController.java)
- [GlobalExceptionHandler.java](/Users/lejinbo/LLM/code-snippets/governance-suite/common/src/main/java/com/lx/governance/common/web/GlobalExceptionHandler.java)
- [TraceIdFilter.java](/Users/lejinbo/LLM/code-snippets/governance-suite/common/src/main/java/com/lx/governance/common/web/TraceIdFilter.java)

### 这部分源码解读要回答什么

- HTTP 请求是怎么找到 Controller 方法的
- `@RequestBody`、`@PathVariable` 是怎么解析的
- 异常为什么会被 `@RestControllerAdvice` 统一处理
- Filter 为什么能在进入 Controller 前后工作

### 建议进入的源码入口

- `org.springframework.web.servlet.DispatcherServlet`
- `org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping`
- `org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter`
- `org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver`
- `org.springframework.web.filter.OncePerRequestFilter`

### 建议阅读顺序

1. 从 `DispatcherServlet#doDispatch` 开始
2. 看请求如何命中 `HandlerMethod`
3. 看参数解析与消息转换
4. 再看异常解析器如何把异常转成统一响应

## 4. Spring WebFlux 与 Gateway 请求链

这部分对应 `gateway`，它不是 Spring MVC，而是基于 WebFlux 和 Reactor。

### 先看项目里的入口

- [TraceIdGlobalFilter.java](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/java/com/lx/governance/gateway/filter/TraceIdGlobalFilter.java)
- [SentinelGatewayConfig.java](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/java/com/lx/governance/gateway/config/SentinelGatewayConfig.java)
- [application.yml](/Users/lejinbo/LLM/code-snippets/governance-suite/gateway/src/main/resources/application.yml)

### 这部分源码解读要回答什么

- Gateway 请求为什么是基于 `Mono` 和过滤器链
- `GlobalFilter` 是如何参与整个转发链路的
- 为什么 MVC Filter 和 Gateway Filter 不是一套机制

### 建议进入的源码入口

- `org.springframework.web.reactive.DispatcherHandler`
- `org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping`
- `org.springframework.cloud.gateway.handler.FilteringWebHandler`
- `org.springframework.cloud.gateway.filter.GlobalFilter`
- `org.springframework.web.server.adapter.HttpWebHandlerAdapter`

### 建议阅读顺序

1. 先看 WebFlux 请求如何进入 `DispatcherHandler`
2. 再看 Gateway 如何选路由
3. 看过滤器链如何串联前置、后置逻辑

## 5. AOP 与声明式能力

这个项目里很多“看起来像魔法”的能力，本质都建立在 AOP 或 BeanPostProcessor 之上。

### 先看项目里的入口

- [OrderAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/OrderAppService.java)
- [InventoryAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/inventory-service/src/main/java/com/lx/governance/inventory/service/InventoryAppService.java)
- [PaymentAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/payment-service/src/main/java/com/lx/governance/payment/service/PaymentAppService.java)

### 这部分源码解读要回答什么

- 注解为什么能变成运行时行为
- 为什么有的方法调用会进入代理，有的不会
- AOP、事务、Sentinel、Seata 这些能力为什么都依赖代理链

### 建议进入的源码入口

- `org.springframework.aop.framework.ProxyFactory`
- `org.springframework.aop.framework.CglibAopProxy`
- `org.springframework.aop.framework.JdkDynamicAopProxy`
- `org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator`
- `org.springframework.aop.framework.ReflectiveMethodInvocation`

### 建议阅读顺序

1. 先理解 Bean 是怎么被包装成代理的
2. 再看方法调用如何进入拦截器链
3. 然后再回去读 Seata / Sentinel 的切面与拦截器

## 6. Spring 事务

这个项目里既有本地事务，也有 Seata 全局事务。先把 Spring 本地事务链读懂，再去看 Seata 会更稳。

### 先看项目里的入口

- [InventoryAppService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/inventory-service/src/main/java/com/lx/governance/inventory/service/InventoryAppService.java)
- [SeckillOrderStreamConsumer.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/SeckillOrderStreamConsumer.java)

### 这部分源码解读要回答什么

- `@Transactional` 为什么能开启事务
- `TransactionTemplate` 为什么能编程式控制事务
- Spring 事务边界和代理边界是什么关系

### 建议进入的源码入口

- `org.springframework.transaction.interceptor.TransactionInterceptor`
- `org.springframework.transaction.interceptor.TransactionAspectSupport`
- `org.springframework.transaction.support.TransactionTemplate`
- `org.springframework.jdbc.datasource.DataSourceTransactionManager`

### 建议阅读顺序

1. 先看 `@Transactional` 如何进入 `TransactionInterceptor`
2. 看事务开启、提交、回滚模板
3. 再看 `TransactionTemplate` 的显式事务写法

## 7. 事件机制

### 先看项目里的入口

- [NacosRegistrationRetryRunner.java](/Users/lejinbo/LLM/code-snippets/governance-suite/common/src/main/java/com/lx/governance/common/cloud/NacosRegistrationRetryRunner.java)

### 这部分源码解读要回答什么

- `@EventListener(ApplicationReadyEvent.class)` 为什么会在应用启动完成后执行
- Spring 的事件发布和监听器模型是什么

### 建议进入的源码入口

- `org.springframework.context.event.EventListenerMethodProcessor`
- `org.springframework.context.event.ApplicationEventMulticaster`
- `org.springframework.boot.context.event.ApplicationReadyEvent`
- `org.springframework.context.support.AbstractApplicationContext#publishEvent`

## 8. 定时任务

### 先看项目里的入口

- [OutboxPublisher.java](/Users/lejinbo/LLM/code-snippets/governance-suite/outbox-consumer/src/main/java/com/lx/governance/outbox/service/OutboxPublisher.java)
- [SeckillOrderStreamConsumer.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/service/SeckillOrderStreamConsumer.java)

### 这部分源码解读要回答什么

- `@Scheduled` 是怎么被发现并注册执行的
- 为什么 `fixedDelay` 会表现成当前任务结束后再等待
- 调度线程池是怎么接管这些任务的

### 建议进入的源码入口

- `org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor`
- `org.springframework.scheduling.config.ScheduledTaskRegistrar`
- `org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler`

## 9. Feign 与声明式 HTTP 客户端

从“组件角度”你已经可以去看 Nacos 和 LoadBalancer；从 Spring 角度，还应该看 `@FeignClient` 自己是怎么变成 Bean 的。

### 先看项目里的入口

- [InventoryClient.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/client/InventoryClient.java)
- [PaymentClient.java](/Users/lejinbo/LLM/code-snippets/governance-suite/order-service/src/main/java/com/lx/governance/order/client/PaymentClient.java)

### 建议进入的源码入口

- `org.springframework.cloud.openfeign.EnableFeignClients`
- `org.springframework.cloud.openfeign.FeignClientsRegistrar`
- `org.springframework.cloud.openfeign.FeignClientFactoryBean`

## 10. 怎么把 Spring 源码和组件源码一起读

推荐顺序：

1. 先用本项目找一个具体入口
2. 先读 Spring 负责“装配和调度”的部分
3. 再读中间件自己“做事情”的部分

例如：

- 先读 `@EventListener` 是怎么触发的，再读 Nacos 注册逻辑
- 先读 Spring AOP 和事务拦截器，再读 Seata 的全局事务拦截
- 先读 Gateway 过滤器链，再读 Sentinel Gateway Filter

这样你更容易分清：

- 哪部分是 Spring 的基础设施能力
- 哪部分是上层分布式组件自己的实现
