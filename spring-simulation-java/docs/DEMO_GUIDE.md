# Demo Guide

项目里的示例按容器主线和专题实验组织。

## 主线 Demo

### `com.spring.simulation.examples.context.SpringRefreshDemo`

这是最重要的入口，后续 IoC 容器相关增强优先都在这里验证。

覆盖内容：

- `ApplicationContext.refresh()` 12 阶段
- 组件扫描与 `BeanDefinition` 注册
- 依赖注入与 Bean 获取
- 主线业务调用
- 最小 AOP 集成示例

适用场景：

- 想理解容器启动主链
- 想给 IoC 增加 `@Qualifier`、`@Primary`、更完整的 `BeanDefinition`
- 想验证“新增能力是否真的进入 refresh 流程”

## 专题 Demo

### `com.spring.simulation.examples.context.SpringAdvancedDemo`

面向“容器高级机制联调”，不要作为基础功能堆叠入口。

覆盖内容：

- 生命周期回调
- `BeanPostProcessor` / `BeanFactoryPostProcessor`
- 循环依赖与三级缓存
- 事务拦截
- 事件机制
- 容器关闭与销毁

### `com.spring.simulation.examples.context.SpringEnhancedDemo`

面向“扩展能力和实验性特性”。

覆盖内容：

- SpEL
- 切点表达式
- 作用域
- 事务传播行为
- `@Configuration` / `@Bean`

### `com.spring.simulation.examples.aop.AopExecutionOrderDemo`

只讲拦截器执行链路。

### `com.spring.simulation.examples.aop.AopOrderedDemo`

只讲 `Ordered` / `PriorityOrdered` 的排序规则。

### `com.spring.simulation.examples.transaction.H2OrderExperiment`

通过 `sh scripts/test-h2.sh --demo` 运行，比较创建订单与写审计记录在 REQUIRED / REQUIRES_NEW / NESTED 下的真实数据库结果。对应回归入口为 `sh scripts/test-h2.sh`，详见 [H2 实验说明](transaction/H2_TRANSACTION_EXPERIMENT.md)。

### `com.spring.simulation.examples.transaction.H2RollbackRuleExperiment`

运行 `sh scripts/test-h2.sh --rules-demo` 展示默认受检异常、默认运行时异常及显式回滚/不回滚的数据库结果。定向回归入口为 `sh scripts/test-h2.sh --rules`，详见 [回滚规则实验](transaction/ROLLBACK_RULE_EXPERIMENT.md)。

## 为什么删除了两个旧 Demo

### 删除 `SpringSimulationDemo`

原因：

- 和 `SpringRefreshDemo` 的覆盖范围高度重叠
- 都在讲基础 IoC + AOP
- 会分散“阶段 1 应该从哪个入口改”的注意力

### 删除 `AutowiredDemo`

原因：

- `@Autowired` 本来就是 `refresh()` 主链的一部分
- 独立存在时容易把注入理解成“旁路功能”
- 后续容器深化应该通过主线 demo 验证注入能力

## 推荐使用方式

1. 改 IoC 主链时，只跑 `SpringRefreshDemo`
2. 改循环依赖、生命周期、事务联调时，跑 `SpringAdvancedDemo`
3. 改 SpEL、scope、配置类解析时，跑 `SpringEnhancedDemo`
4. 改 AOP 拦截器顺序时，跑两个 `aop` 专题 demo
