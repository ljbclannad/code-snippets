# 文档导航

这个目录按主题分类当前项目的学习文档，根目录只保留总入口 [README.md](/Users/lejinbo/LLM/code-snippets/spring-simulation-java/README.md:1)。

## 分类

### `aop/`
- `AOP_EXECUTION_ORDER.md`: 拦截器执行顺序拆解
- `AOP_INTERCEPTOR_CHAIN.md`: 拦截器链与责任链执行模型
- `AOP_ORDERED.md`: `Ordered` / `PriorityOrdered` 的排序时机
- `AOP_VISUAL_SUMMARY.md`: AOP 执行流程可视化总结

### `ioc/`
- `AUTOWIRED_FLOW.md`: `@Autowired` 在 `refresh()` 中的处理流程
- `THREE_LEVEL_CACHE.md`: 三级缓存与循环依赖

### `transaction/`
- `ROLLBACK_RULE_EXPERIMENT.md`: rollbackFor/noRollbackFor、受检异常与运行时异常对照
- `NESTED_SAVEPOINT_EXPERIMENT.md`: JDBC 保存点、三种传播对照与故障处理
- `H2_TRANSACTION_EXPERIMENT.md`: H2 真实订单/审计事务、运行入口与数据库回归
- `TRANSACTION_REGRESSION.md`: 跨 Bean 的 REQUIRED / REQUIRES_NEW 事务回归实验

### `architecture/`
- `DESIGN.md`: 容器、AOP、事务等核心设计原理总览

## 规划文档
- `DEMO_GUIDE.md`: 当前 demo 的职责划分与使用建议
- `SPRING_SECONDARY_DEVELOPMENT_ROADMAP.md`: 二开 Spring 学习路线图
- `SPRING_SIMULATION_TODO.md`: 当前项目的演进 TODO
