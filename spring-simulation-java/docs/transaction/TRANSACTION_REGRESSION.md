# Spring 事务传播回归实验

后续的真实 JDBC 数据验证见 [H2 订单与审计实验](H2_TRANSACTION_EXPERIMENT.md)。本文保留状态模拟实验的边界。

## 实验范围

本实验验证本项目的教学事务状态模型，入口为 `src/test/java/com/spring/simulation/TransactionRegressionTests.java`。没有引入真实 Spring、JDBC 或数据库，因此结果证明的是传播状态与提交/回滚调用顺序，不代表已经验证数据库数据的持久化或撤销。

外层和内层是两个独立 Bean，均实现业务接口，通过 `AopAutoProxyCreator` 创建 JDK 代理。外层通过注入的内层接口调用事务方法，避免同类自调用绕过代理。两个代理使用同一个容器事务管理器。

## 运行

在项目根目录运行完整验证：

```sh
sh scripts/test.sh
```

只运行本实验（构建目录由系统创建，保留供检查）：

```sh
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/spring-tx-regression.XXXXXX")
find src/main/java src/test/java -name '*.java' > "$build_dir/sources"
javac -encoding UTF-8 -d "$build_dir/classes" @"$build_dir/sources"
java -cp "$build_dir/classes:src/main/resources" com.spring.simulation.TransactionRegressionTests
```

成功时输出 `事务回归实验通过：4 组`；断言失败抛出 `AssertionError` 并以非零状态退出，不需要启用 `-ea`。

## 四组场景

外层传播行为均为 `REQUIRED`。

| 内层传播行为 | 执行过程 | 预期完成顺序 |
| --- | --- | --- |
| REQUIRED | 内层正常返回，外层随后抛异常 | 参与者返回 → 外层共享事务回滚 |
| REQUIRES_NEW | 内层正常返回，外层随后抛异常 | 内层独立提交 → 外层回滚 |
| REQUIRED | 内层抛异常，外层捕获并正常返回 | 参与者标记 rollback-only → 外层提交被拒绝并回滚 |
| REQUIRES_NEW | 内层抛异常，外层捕获并正常返回 | 内层独立回滚 → 外层正常提交 |

### REQUIRED：方法返回不等于共享事务提交

外层开启事务 A，内层建立一个加入 A 的逻辑调用状态。内层拦截器执行 `commit(innerStatus)` 只完成该逻辑调用，不完成 A。内层返回后，外层仍持有活跃事务 A；外层异常使 A 回滚。

`getCurrentTransaction()` 返回当前逻辑调用的 owner。因此测试在内层记录的事务与外层相同，内层方法返回后该 owner 的 `isCompleted()` 仍为 `false`。不能把内层拦截器的 `Completed transaction` 日志理解为独立事务已经提交。

本实验内层抛出的是默认触发回滚的运行时异常，参与者回滚会将 A 标记为 rollback-only；外层即使捕获异常，最终提交仍会失败。本项目使用带 `rollback-only` 信息的 `IllegalStateException` 表达这一结果。

### REQUIRES_NEW：挂起、独立完成、恢复

外层开启事务 A，内层挂起 A 并开启事务 B。测试在内层断言 B 与 A 不是同一个状态、B 活跃且 A 已挂起。内层正常返回时提交 B，异常时回滚 B，然后恢复 A。

外层继续执行时，测试断言当前事务重新成为 A、A 活跃且未挂起。B 已完成；外层回滚不会对 B 再次调用回滚。内层异常被外层捕获时，A 不带 rollback-only 标记，可以正常提交。

## 验证方式与边界

测试专用 `RecordingManager` 调用父类的真实状态转换方法后记录完成顺序；它不替换传播逻辑，也不模拟数据存储。每组还验证代理生效、异常传播、正常返回/捕获路径、最终事务状态完成，以及 `getCurrentTransaction()` 为空。

本次仅新增测试、接入测试脚本并更新文档，没有修改生产事务代码。未覆盖真实数据库、连接池、隔离级别、超时或保存点语义。
