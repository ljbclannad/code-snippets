# 事务回滚规则实验

## 行为与兼容性

`@Transactional` 新增类型数组属性 `rollbackFor` 和 `noRollbackFor`。默认行为从“所有抛出的异常都回滚”改为：`RuntimeException`、`Error` 回滚，受检异常提交后继续抛出。这与 [Spring 默认回滚规则及类型匹配说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)的基本规则一致。

既有 H2 传播实验中的订单、审计方法显式增加 `rollbackFor = SQLException.class`，保留 SQL 失败时回滚的实验约定。其他事务方法若依赖原来的“受检异常也回滚”，应显式添加对应类型；`rollbackFor = Exception.class` 可以覆盖所有 Exception 子类。

## 运行

```sh
sh scripts/test-h2.sh --rules       # 14 组回滚规则 H2 回归
sh scripts/test-h2.sh --rules-demo  # 展示四组核心对照
sh scripts/test-h2.sh              # 18 组传播/保存点 + 14 组规则回归
sh scripts/test.sh --h2            # 再加原有回归与主要示例
```

依赖仍为固定版本 H2 2.4.240，可通过 `H2_JAR` 指定本地 JAR，详见 [H2 实验说明](H2_TRANSACTION_EXPERIMENT.md)。

## 订单与审计对照

外层先插入订单，再通过另一个 Bean 的 REQUIRED 代理插入审计。内层成功返回后，外层抛出指定业务异常。两次 INSERT 已成功，最终记录数能直接证明事务是提交还是回滚，而非仅证明 SQL 没有执行。

| 外层规则 | 业务异常 | 订单 / 审计 | 调用方结果 |
| --- | --- | --- | --- |
| 默认 | 受检 Exception | 1 / 1 | 收到原异常 |
| 默认 | IllegalArgumentException | 0 / 0 | 收到原异常 |
| 默认 | Error | 0 / 0 | 收到原错误 |
| rollbackFor = Exception.class | 受检 IOException | 0 / 0 | 收到原异常 |
| noRollbackFor = RuntimeException.class | IllegalArgumentException | 1 / 1 | 收到原异常 |

`noRollbackFor` 不是吞掉异常，也不意味着接口调用成功。它只决定该事务边界遇到异常时尝试提交还是回滚。

## 匹配规则

```java
@Transactional(
    rollbackFor = Exception.class,
    noRollbackFor = IllegalArgumentException.class
)
public void createOrder() {
    // IllegalArgumentException 更具体，因此尝试提交。
}
```

匹配按实际抛出异常的类及其父类计算继承距离，距离最小的规则胜出；数组元素顺序不会改变不同距离规则的结果。同一类型同时出现在两个数组时，本项目约定 `rollbackFor` 优先。没有匹配时回退默认规则。

目前只支持异常 Class 类型，不支持类名字符串/模式匹配；不沿 `cause` 链查找规则。若受检异常被包装成运行时异常，按外层实际抛出的异常类型判断。

注解查找保持现有优先级：实现类方法 → 接口方法 → 目标类。方法注解完整覆盖类级注解，不合并规则。因此类上声明 `rollbackFor = Exception.class`，方法上只写 `@Transactional` 时，该方法重新使用默认回滚规则。

## 与传播行为的关系

规则在每个代理边界分别判断。REQUIRED 的内层回滚可能已经给共享事务设置 rollback-only；外层的 `noRollbackFor` 不能清除此标记。

专项用例先写订单，再调用会失败的 REQUIRED 审计方法。内层默认回滚并标记共享事务；外层虽然为该异常声明 `noRollbackFor`，其提交仍被拒绝，最终两表均为 0。调用方收到 rollback-only 的完成异常，原始业务异常保存在 `getSuppressed()` 中。

REQUIRES_NEW 规则控制独立事务；NESTED 规则控制是否回滚到保存点。内层“不回滚”不保证数据最终持久化：共享外层事务之后仍可能回滚。

## 回归与实现

14 组 H2 测试覆盖正常返回、默认受检/运行时/Error、规则数组及父类型匹配、两个方向的最具体匹配、同类型冲突、无匹配回退、类级规则、方法覆盖、接口方法注解，以及已有 rollback-only 标记。

每组使用独立连接检查订单与审计记录，验证原始业务异常对象继续传播、连接关闭和线程事务状态清理。事务完成失败优先抛出完成异常，并将业务异常作为 suppressed 保留；正常返回后的提交失败不会重新进入业务异常规则处理。

- `src/main/java/com/spring/simulation/transaction/Transactional.java`：新增类型数组属性。
- `src/main/java/com/spring/simulation/transaction/TransactionInterceptor.java`：类型匹配与异常完成流程。
- `src/main/java/com/spring/simulation/examples/transaction/H2RollbackRuleExperiment.java`：订单/审计回滚规则示例。
- `src/test/java/com/spring/simulation/RollbackRuleRegressionTests.java`：14 组数据库回归。

本次仍是教学框架的同步方法事务，不支持 Future/Vavr 返回值回滚识别、全局默认回滚策略配置或完整的 Spring 异常类型体系。
