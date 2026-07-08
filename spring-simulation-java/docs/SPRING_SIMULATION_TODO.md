# Spring Simulation TODO

这份 TODO 以“继续演进这个项目”为目标，不是泛泛而谈的愿望清单。

## 当前结构

经过本轮整理，代码可以按下面的心智模型来读：

- `ioc/`: 容器内核
- `aop/`: 代理与拦截器链
- `transaction/`: 事务概念实现
- `spel/`: 表达式与值解析
- `event/`: 事件模型
- `examples/`: 所有可运行入口
- `demo/app/`: 示例业务 Bean
- `demo/extension/`: 容器扩展点示例
- `demo/listener/`: 事件监听器示例
- `demo/guide/`: 讲解型 demo
- `constructor/`: 构造注入/Aware 专题
- `circular/`: 循环依赖专题

## P0：立即值得补

### IoC

- [ ] `BeanDefinition` 增加 `lazyInit`
- [ ] `BeanDefinition` 增加 `primary`
- [ ] `BeanDefinition` 增加 `initMethodName/destroyMethodName`
- [ ] 支持 `@Qualifier`
- [ ] 多实现冲突时输出更清晰的异常
- [ ] setter 注入支持多参数方法的语义校验

### AOP

- [ ] 新增 `Advisor` 抽象
- [ ] 区分 `Advice`、`Pointcut`、`Advisor`
- [ ] 支持容器自动代理，而不是 demo 中手动创建代理
- [ ] 三级缓存暴露早期代理

### 事务

- [ ] 将 `TransactionManager` 从“概念演示”推进到“资源绑定模型”
- [ ] 支持回滚规则
- [ ] 支持保存点语义
- [ ] 支持事务同步回调

### 测试

- [ ] 为 `BeanFactory` 建单测
- [ ] 为循环依赖场景建回归测试
- [ ] 为 `@Autowired` 多实现选择建测试
- [ ] 为 AOP 拦截顺序建测试

## P1：进入框架化演进

### 配置与扫描

- [ ] 将 `AnnotationConfigApplicationContext` 拆成扫描、解析、注册三层
- [ ] 支持 `@Import`
- [ ] 支持 `ImportSelector`
- [ ] 支持 `ImportBeanDefinitionRegistrar`
- [ ] 引入简单 `Condition` 机制
- [ ] 替换当前文件系统扫描为更通用的类路径扫描方案

### 生命周期与扩展点

- [ ] 把 `AutowiredAnnotationBeanPostProcessor` 从 demo 级别提升到正式扩展点
- [ ] 增加 `InstantiationAwareBeanPostProcessor`
- [ ] 增加 `SmartInstantiationAwareBeanPostProcessor`
- [ ] 区分原始 Bean、早期引用、最终暴露 Bean

### 环境与配置

- [ ] 抽象 `PropertySource`
- [ ] 补全 `${}` 占位符链路
- [ ] 支持 profile
- [ ] 支持简单类型转换

## P2：学习价值高，但可以稍后做

### SpEL

- [ ] 支持更完整的布尔表达式
- [ ] 支持方法参数调用
- [ ] 支持对象属性链式访问
- [ ] 支持集合和 map 更完整的访问语法

### 事件

- [ ] 支持按事件类型过滤监听器
- [ ] 支持异步事件派发
- [ ] 支持监听器排序

### 文档

- [ ] 为 `refresh()` 12 步补时序图
- [ ] 为三级缓存补对象状态图
- [ ] 为自动代理补时序图
- [ ] 为事务传播补调用栈图

## 结构继续细分的建议

当前结构已经比之前清晰一些，但如果后续功能继续加，建议按下面方向继续分：

### `ioc/` 未来可拆

- `ioc/context/`: `ApplicationContext`、`AnnotationConfigApplicationContext`
- `ioc/factory/`: `BeanFactory`
- `ioc/definition/`: `BeanDefinition`、`BeanScope`
- `ioc/postprocessor/`: BPP、BFPP 相关接口
- `ioc/annotation/`: `@Component`、`@Autowired`、`@Bean`
- `ioc/aware/`: 各种 Aware 接口

### `aop/` 未来可拆

- `aop/proxy/`: JDK/CGLIB 代理
- `aop/advice/`: Before/Around/After
- `aop/interceptor/`: `MethodInterceptor`、`MethodInvocation`
- `aop/pointcut/`: 切点解析器和表达式
- `aop/order/`: `Ordered`、`PriorityOrdered`

### `transaction/` 未来可拆

- `transaction/annotation/`
- `transaction/interceptor/`
- `transaction/core/`
- `transaction/support/`

## 本轮整理后的建议阅读顺序

1. `examples/context/SpringRefreshDemo`
3. `ioc/ApplicationContext`
4. `ioc/BeanFactory`
5. `demo/app/*`
6. `aop/*`
7. `transaction/*`
7. `examples/context/SpringAdvancedDemo`

## 完成定义

这个项目什么时候算从“教学 demo”升级到“可二开的迷你 Spring”？

满足下面几点时，就算迈过关键门槛：

- IoC 装配规则可配置，不靠硬编码约定
- AOP 代理由容器自动创建
- 事务增强不需要手工包代理
- 核心语义有测试守护
- 文档能解释设计取舍，而不只是描述功能
