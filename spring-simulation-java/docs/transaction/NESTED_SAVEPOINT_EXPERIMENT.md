# NESTED 保存点实验

## 目标与验收

在现有 H2 订单/审计实验上支持真实 JDBC 保存点。沿用项目自己的代理与注解，不引入 Spring Framework。验收关注数据库最终记录、连接复用、保存点释放以及失败后的事务状态清理。

NESTED 采用单个物理事务内的保存点实现局部回滚，这与 [Spring 对 NESTED 的说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)一致。底层使用 JDBC 的 `setSavepoint()`、`rollback(Savepoint)` 与 `releaseSavepoint()`，方法契约见 [JDBC Connection 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/Connection.html)。

## 运行

在项目根目录执行：

```sh
sh scripts/test-h2.sh         # 32 组 H2 回归，含 6 组保存点专项
sh scripts/test-h2.sh --demo  # 展示三种传播 × 三种业务结果
sh scripts/test.sh --h2       # 兼顾原有回归及示例
```

使用固定版本 H2 2.4.240，也可通过 `H2_JAR=/absolute/path/h2-2.4.240.jar` 离线运行。构建方式与依赖校验详见 [H2 实验说明](H2_TRANSACTION_EXPERIMENT.md)。

## 三种传播的区别

外层始终是 REQUIRED：先写订单，再通过另一个 Bean 的代理写审计。

| 审计传播 | 连接 | 内层正常返回 | 内层失败被外层捕获 | 内层成功后外层失败 |
| --- | --- | --- | --- | --- |
| REQUIRED | 共用外层连接 | 不提交物理事务 | 共享事务标记回滚，订单 0、审计 0 | 订单 0、审计 0 |
| REQUIRES_NEW | 独立连接 | 独立提交 | 订单 1、审计 0 | 订单 0、审计 1 |
| NESTED | 共用外层连接 | 释放保存点，不提交 | 回滚到保存点，订单 1、审计 0 | 订单 0、审计 0 |

全部正常时，三种传播均得到订单 1、审计 1。查询使用独立连接，验证的是外部可见的已提交数据。

### 执行顺序

1. 外层关闭连接 A 的自动提交，并插入订单。
2. 内层 NESTED 在 A 上创建保存点 S，然后插入审计记录。
3. 内层成功则释放 S；内层失败则回滚到 S，再释放 S。两条路径都不关闭 A。
4. 恢复外层逻辑状态，继续使用 A。外层提交时，剩余修改一起提交；外层回滚时，全部修改一起撤销。

释放保存点不是提交。保存点之后写入的审计仍属于外层物理事务，不会因为内层正常返回而获得独立持久化保证。

## 实现边界

`JdbcTransactionManager.Frame` 保存当前逻辑状态、连接与可选的 `Savepoint`。原有默认 `TransactionManager` 仍只模拟状态，只有 JDBC 扩展建立真实保存点。

- 无外层事务：NESTED 创建普通新事务，直接执行物理提交/回滚。
- 多层嵌套：每层独立保存点，复用同一个连接；必须逆序完成，重复或乱序完成在访问数据库前被拒绝。
- NESTED 内再调用 REQUIRED：在本教学模型中，参与者回滚会标记当前嵌套 owner。嵌套提交时回滚到保存点并抛出 rollback-only 异常；外层捕获后仍可提交。该组合由本项目专项测试定义，未声称覆盖真实 Spring 的全部回滚标记策略。
- 创建保存点失败：撤销尚未进入业务方法的嵌套逻辑状态，恢复外层连接；外层捕获后可以重试。驱动不支持保存点时直接失败，不降级为 REQUIRED。
- 保存点回滚或释放失败：无法保证局部完成，标记同连接所有活跃作用域为 rollback-only，避免外层捕获异常后误提交。外层提交将执行完整回滚并报错。

保存点仅撤销数据库修改，不恢复 Java 对象字段、消息发送或其他外部副作用。未实现连接池、事务超时，也未覆盖网络中断下的所有驱动行为。支持类型化回滚规则，参见 [回滚规则实验](ROLLBACK_RULE_EXPERIMENT.md)。

## 六组专项回归

`NestedJdbcRegressionTests` 使用真实 H2，并在故障测试中以连接代理精确注入指定 JDBC 方法异常，其余方法委托给真实连接。

1. 无外层 NESTED 的正常提交与完整回滚，确认没有建立保存点。
2. 多层保存点：最内层回滚保留中层/外层写入；中层正常完成不会让独立连接读到未提交数据；验证两次创建与两次释放、乱序/重复保护，以及外层继续写入后提交。
3. NESTED 内 REQUIRED 参与者回滚：回滚到保存点，外层数据保留并可提交。
4. `setSavepoint` 失败：恢复外层，重试嵌套调用并正常提交。
5. `rollback(Savepoint)` 失败：外层提交必须被拒绝，最终无数据残留。
6. `releaseSavepoint` 失败：外层提交必须被拒绝，最终无数据残留。

每组完成后检查连接关闭、当前事务为空和当前连接不可再获取。另有 9 组业务组合与 3 组已有 JDBC 边界回归，总计 18 组。
