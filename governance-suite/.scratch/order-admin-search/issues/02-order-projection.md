# 02 — 订单后台读模型的订单投影

**What to build:** 订单事件可将订单事实写入独立、非分片的订单后台读模型；重复事件幂等，旧订单事件不会覆盖新投影。

**Blocked by:** None — can start immediately.

**Status:** completed

- [x] 新建、更新和取消订单均能形成正确的订单后台读模型投影。
- [x] 已取消订单作为历史交易记录继续保留并可被后续检索。
- [x] 重复事件不产生重复或错误更新，旧订单事件不覆盖新订单投影。

## 验收记录

- 已移除 Testcontainers 验证路径，改由本地 MySQL（`order_db`）执行真实仓储投影测试；测试不执行 DDL 或整表清理。
- 每个场景生成唯一订单号，在本地事务中写入并回滚；测试结束后断言无残留记录。
- 验收命令：`JAVA_HOME=$(/usr/libexec/java_home -v 17) PATH="$JAVA_HOME/bin:$PATH" mvn -pl distributed/order-service -am -Dtest=OrderAdminProjectionServiceTest,OrderAdminReadModelRepositoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 2026-09-15 实测结果：共 6 项测试通过，0 失败、0 错误、0 跳过；覆盖创建、更新、取消、重复事件和旧版本事件。
