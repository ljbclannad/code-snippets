# Spring 二开学习路线图

这份路线图面向两类目标同时设计：

1. 深度理解 Spring 的设计动机，而不是只会背名词。
2. 基于本项目逐步演进出一套“可二开、可裁剪、可验证”的迷你 Spring。

## 先看清当前基线

当前项目已经覆盖了这些核心概念：

- IoC 容器主干：`ApplicationContext`、`BeanFactory`、`BeanDefinition`
- 生命周期与扩展点：`BeanPostProcessor`、`BeanFactoryPostProcessor`、Aware、初始化/销毁
- 三级缓存和循环依赖
- 基础 AOP：JDK 动态代理、拦截器链、执行顺序
- 事务概念：`@Transactional`、传播行为、事务拦截器
- 事件、SpEL、`@Configuration/@Bean`

当前最适合的学习入口：

- `com.spring.simulation.examples.context.SpringRefreshDemo`
- `com.spring.simulation.examples.context.SpringAdvancedDemo`
- `com.spring.simulation.examples.context.SpringEnhancedDemo`
- `com.spring.simulation.examples.aop.AopExecutionOrderDemo`
- `com.spring.simulation.examples.aop.AopOrderedDemo`

说明：

- `SpringRefreshDemo` 是主线 demo，阶段 1 的 IoC 深化优先从这里推进
- `SpringAdvancedDemo` 和 `SpringEnhancedDemo` 保持专题角色，不再承担基础入口职责

## 阶段 1：把 IoC 容器做深

目标：从“会创建 Bean”升级到“能稳定描述和解析 Bean”。

重点主题：

- `BeanDefinition` 元数据扩展
- 按类型注入、按名称注入、候选选择规则
- `@Qualifier`、`@Primary`
- 构造器解析策略
- `ObjectProvider`/延迟注入
- 循环依赖和早期代理的一致性

建议阅读顺序：

1. `ioc/BeanFactory.java`
2. `ioc/ApplicationContext.java`
3. `demo/app/*`
4. `demo/extension/*`

实践任务：

1. 给 `BeanDefinition` 增加 `lazyInit`、`primary`、`dependsOn`、`initMethodName`、`destroyMethodName`
2. 实现 `@Qualifier`
3. 实现同类型多 Bean 冲突异常信息
4. 为字段注入、setter 注入、构造器注入补测试

阶段验收：

- 同一个接口多个实现时能稳定选择目标 Bean
- 构造器注入和字段注入的报错信息可读
- 循环依赖场景下不会重复创建实例

## 阶段 2：把容器扩展点做成框架能力

目标：从“能跑通流程”升级到“扩展点可组合、可演化”。

重点主题：

- `BeanPostProcessor` 分层
- `InstantiationAwareBeanPostProcessor`
- `SmartInstantiationAwareBeanPostProcessor`
- 提前暴露代理
- `MergedBeanDefinition` 思想
- `FactoryBean` 与普通 Bean 的区别

建议重点补齐：

1. 把 `@Autowired` 处理从示例处理器升级为容器正式能力
2. 将 AOP 早期代理接到三级缓存暴露点
3. 区分“创建原始对象”和“返回最终暴露对象”

阶段验收：

- 能解释为什么 Spring 要有这么多 PostProcessor 子接口
- 同一个 Bean 可以同时被自动注入、AOP、事务增强
- 早期引用与最终引用不冲突

## 阶段 3：把配置解析做成真正的启动链

目标：从“反射扫一遍类”升级到“配置驱动容器组装”。

重点主题：

- `@Configuration` 的完整解析链
- `@Import`
- `ImportSelector`
- `ImportBeanDefinitionRegistrar`
- 条件装配
- 类路径扫描和元数据读取

建议改造点：

1. 将 `AnnotationConfigApplicationContext` 拆成扫描、解析、注册三个步骤
2. 引入简单的 `Condition` 接口
3. 支持 `@Bean` 方法参数注入
4. 支持 `@Configuration` 递归解析

阶段验收：

- 可以不手工注册，也能靠配置类组装容器
- 能实现最小版 “条件成立才注册 Bean”
- 能解释 Spring Boot 自动配置是怎么建立在这层之上的

## 阶段 4：把 AOP 和事务做成系统能力

目标：从“手动创建代理”升级到“容器自动代理”。

重点主题：

- Advisor / Advice / Pointcut 分层
- 自动代理创建器
- 代理工厂
- 代理选择：JDK vs CGLIB
- 事务资源绑定
- 事务同步回调

建议任务：

1. 新增 `Advisor` 抽象
2. 新增 `AutoProxyCreator`
3. 支持扫描 `@Transactional` 自动创建事务代理
4. 为 `TransactionManager` 增加资源绑定模型

阶段验收：

- 用户不需要手动 new `JdkDynamicAopProxy`
- `@Transactional` 可以由容器自动生效
- 多个拦截器的顺序可控且可测试

## 阶段 5：进入“Spring Boot 化”思维

目标：从“框架原理项目”升级到“能被别人拿来集成的框架”。

重点主题：

- `Environment` / `PropertySource`
- 占位符解析
- 自动配置
- Starter 思维
- 测试基础设施
- 失败分析与诊断信息

建议任务：

1. 增加 `PropertySource` 抽象
2. 实现简单的 `@ConditionalOnMissingBean`
3. 实现最小自动配置注册入口
4. 建立 demo 测试和容器测试

阶段验收：

- 有一条最小自动配置链路
- 属性配置可驱动 Bean 行为
- 项目修改后可以靠测试回归

## 学习方式建议

不要按“功能列表”学，按“调用链”学。

推荐节奏：

1. 先跑 demo，看现象
2. 再追核心调用链
3. 然后补一个最小功能
4. 最后用测试把语义固定住

每学完一个阶段，最好自己回答这 3 个问题：

1. 这个抽象解决了什么痛点？
2. 如果没有这个抽象，会在哪一步崩掉？
3. 我现在这份实现和真实 Spring 的差距在哪？

## 推荐的下一步

如果你准备继续沿这条路走，最值得马上做的 3 件事是：

1. 给 IoC 补 `@Qualifier/@Primary`
2. 给 AOP 补自动代理创建器
3. 给项目补最小单元测试框架
