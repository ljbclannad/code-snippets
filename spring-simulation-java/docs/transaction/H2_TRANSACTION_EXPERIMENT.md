# H2 真实事务实验：订单与审计

## 目标与范围

沿用项目自身的 IoC、JDK 代理、`@Transactional` 和事务拦截器，增加真实 JDBC 连接与 H2 内存数据库，比较 `REQUIRED`、`REQUIRES_NEW` 与 `NESTED` 的数据库结果。没有引入 Spring Framework。

默认 `TransactionManager` 仍是原有状态模拟器。新增 `JdbcTransactionManager` 继承其传播状态模型，并管理连接；实验通过覆盖 `BeanFactory.getTransactionManager()` 为同一容器的两个业务代理提供同一个 JDBC 管理器。

实现 REQUIRED、REQUIRES_NEW 和基于 JDBC 保存点的 NESTED。其他传播行为会明确拒绝，未实现连接池、超时及隔离级别/只读注解属性。回滚规则支持 `rollbackFor` / `noRollbackFor`，默认运行时异常和 Error 回滚、受检异常不回滚。本实验显式声明 `rollbackFor = SQLException.class`，保留 SQL 失败时回滚的约定，详见 [回滚规则实验](ROLLBACK_RULE_EXPERIMENT.md)。

## 运行入口

需要 JDK 17 或更高版本。脚本通过 `curl` 从 Maven Central 获取固定版本 H2 2.4.240，并校验固定 SHA-256；也可用 `H2_JAR` 指定该版本的本地 JAR。依赖版本可查阅 [H2 官方发布记录](https://github.com/h2database/h2database/releases/tag/version-2.4.240)。

在项目根目录执行：

```sh
# 32 组真实数据库回归（18 组传播/保存点 + 14 组回滚规则）
sh scripts/test-h2.sh

# 展示九种业务组合及最终记录数
sh scripts/test-h2.sh --demo

# 原有回归、6 个主要示例及新增 H2 回归
sh scripts/test.sh --h2

# 离线运行：本地 JAR 也会校验摘要
H2_JAR=/absolute/path/h2-2.4.240.jar sh scripts/test-h2.sh
```

原有 `sh scripts/test.sh` 仍不需要下载依赖。新增脚本只在临时目录构建，不使用现有 `bin`；输出构建目录并保留其中的编译产物/JAR 供检查。下载失败或摘要不匹配直接失败，不静默跳过数据库回归。

## 业务流程和预期结果

`OrderService.create()` 用外层 `REQUIRED` 事务写入订单，通过注入的 `AuditOperations` 代理调用另一 Bean 写审计。审计方法分别声明 `REQUIRED`、`REQUIRES_NEW` 和 `NESTED`。`OrderOperations.create(int, Propagation, Outcome)` 用枚举选择传播行为，替代先前的布尔参数。

| 场景 | 审计传播 | 订单记录 | 审计记录 |
| --- | --- | ---: | ---: |
| 正常返回 | REQUIRED | 1 | 1 |
| 正常返回 | REQUIRES_NEW | 1 | 1 |
| 正常返回 | NESTED | 1 | 1 |
| 审计正常返回后，外层抛异常 | REQUIRED | 0 | 0 |
| 审计正常返回后，外层抛异常 | REQUIRES_NEW | 0 | 1 |
| 审计正常返回后，外层抛异常 | NESTED | 0 | 0 |
| 审计写入后抛异常，外层捕获 | REQUIRED | 0 | 0 |
| 审计写入后抛异常，外层捕获 | REQUIRES_NEW | 1 | 0 |
| 审计写入后抛异常，外层捕获 | NESTED | 1 | 0 |

每组使用独立命名的 H2 内存数据库。一个 keeper 连接维持数据库生命周期；回归断言使用额外的新连接查询已提交数据，实验结束关闭 keeper，数据库随之释放。

### REQUIRED 的连接生命周期

外层开启连接 A，关闭自动提交，写入订单。内层加入外层事务并复用 A，写入审计。内层正常返回仅完成逻辑调用，不提交也不关闭 A；外层提交或回滚时才完成真实数据库事务并关闭 A。

内层异常回滚会标记共享事务 rollback-only。即使外层捕获异常，外层提交时也会真正执行 JDBC rollback，并抛出包含 `rollback-only` 的 `IllegalStateException`。

### REQUIRES_NEW 的连接生命周期

外层连接 A 保持开启且未提交，内层使用新连接 B。B 独立提交或回滚并关闭后，恢复 A 为当前连接。外层后续回滚不会撤销 B 已提交的审计记录。

“挂起”表示当前执行切换到 B；A 仍然占用连接，并可能持有锁。它不是数据库层面的释放连接或释放锁。

### NESTED 的保存点生命周期

外层先在连接 A 写入订单。进入审计方法时仍使用 A，通过 `setSavepoint()` 建立保存点 S，然后写审计。正常返回时只调用 `releaseSavepoint(S)`；不会提交或关闭 A。因此外层随后回滚时，订单和审计都会消失。

审计写入后抛异常时，调用 `rollback(S)` 撤销保存点之后的修改，再释放 S。外层捕获异常后可以继续写入并提交，订单保留，审计不保留。若异常未被外层捕获并继续向外传播，外层拦截器仍会回滚整个事务。

没有外层事务时，NESTED 直接开启新连接和普通事务，不创建保存点。多层 NESTED 在同一个连接上分别建立保存点，按调用栈逆序完成。详细边界和专项测试见 [NESTED 保存点实验](NESTED_SAVEPOINT_EXPERIMENT.md)。

### 为什么审计没有订单外键

审计记录表达“尝试创建订单”，订单失败后仍可保留。审计表用 `order_id` 记录业务关联，但不建立外键，也不查询外层尚未提交的订单。否则独立事务 B 可能无法看到 A 的订单，或产生锁等待，实验就会混入约束与并发可见性问题。

## 回归覆盖

`src/test/java/com/spring/simulation/H2TransactionRegressionTests.java` 使用显式断言，失败会非零退出，不依赖 `-ea`。

- 九组业务组合：检查独立连接查询的记录数、异常结果、连接是否共享、恢复后的外层连接身份、连接关闭和事务上下文清理；随后在同一容器成功创建第二笔订单，验证失败后仍可使用。
- 完成顺序保护：乱序提交不能提前完成数据库事务，内层 REQUIRED 返回不能关闭外层连接，重复完成必须拒绝。
- 开启失败：模拟 REQUIRES_NEW 获取连接失败，验证外层状态、连接恢复且可以继续提交。
- SQL 异常：真实重复主键错误应回滚并清理连接，同时保留先前已提交的数据。

另有 6 组保存点专项回归，覆盖无外层、多层嵌套、嵌套内 REQUIRED 的回滚标记，以及保存点创建、回滚、释放故障。

连接由事务管理器拥有。业务代码只关闭 `PreparedStatement`、`ResultSet`，不能自行关闭、提交或回滚管理器返回的连接。独立事务完成失败时管理器尝试回滚并清理；保存点完成失败时，管理器将同连接各作用域标记为 rollback-only，外层完成时整笔回滚，保留主异常和清理异常；本轮未注入网络中断或提交阶段故障，也不证明分布式故障下的提交结果。

## 源码导航

- `src/main/java/com/spring/simulation/transaction/JdbcTransactionManager.java`：连接创建、共享、切换、完成与清理。
- `src/main/java/com/spring/simulation/examples/transaction/H2OrderExperiment.java`：订单/审计服务、建表、容器装配与可运行示例。
- `src/test/java/com/spring/simulation/H2TransactionRegressionTests.java`：数据库结果回归。
- `src/test/java/com/spring/simulation/NestedJdbcRegressionTests.java`：保存点边界与故障注入回归。
- `scripts/test-h2.sh`：依赖校验、干净编译与执行。

先观察 [状态模拟实验](TRANSACTION_REGRESSION.md)，再运行本实验，可对照逻辑事务完成与真实数据库提交的区别。
