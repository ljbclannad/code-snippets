# 订单后台检索：联调验收与运维

本能力由 `gateway`、`user-service`、`order-service`、`payment-service`、`inventory-service`、`outbox-consumer` 和本地 MySQL/RabbitMQ/Redis/Nacos/Seata 共同组成。订单、支付事实分别留在所属服务；`order-service` 维护最终一致的后台读模型，并在本服务验证后台令牌。库存是查询时实时补充，不参与读模型与 CSV 导出。

## 数据库与启动顺序

新测试环境执行 `distributed/init-databases.sql`；已有环境先盘点列、索引和表，再按需执行下列**非幂等**迁移，不能对已应用的迁移重复执行，也不要为验收清空现有数据库：

| 顺序 | 文件 | 作用 |
| --- | --- | --- |
| 1 | `sql/migrations/20260917_01_order_admin_sort_indexes.sql` | 金额、支付更新时间游标索引 |
| 2 | `sql/migrations/20260917_04_order_admin_operator_search.sql` | 创建时间、客户检索令牌与索引 |
| 3 | `sql/migrations/20260917_05_order_admin_finance_export.sql` | 导出任务、快照行 |
| 4 | `sql/migrations/20260917_06_order_admin_finance_export_snapshot.sql` | 导出冻结范围与水位列 |
| 5 | `sql/migrations/20260917_07_order_admin_projection_rebuild.sql` | 回填任务及审计 |
| 6 | `sql/migrations/20260917_08_order_admin_activity_audit.sql` | 后台操作审计 |
| 7 | `sql/migrations/20260917_09_order_fact_updated_at.sql` | 仅旧环境订单事实分片缺少 `updated_at` 时补列；新初始化环境已有该列 |

确认基础设施健康后启动六个服务；按顺序确认 `/actuator/health`、RabbitMQ 队列声明和数据库迁移。旧读模型行的 `created_at` 可能为空，此类行不会出现在后台列表中，需由 `ORDER_OPERATOR` 发起回填。导出目录应是受控共享目录，不能被 Web 服务器直接暴露。后台角色由受控用户角色表授予，公开注册接口不能创建管理员。

## 接口与权限

网关默认使用服务发现路由：后台路径为 `/order-service/admin/orders`；直连订单服务为 `/admin/orders`。普通创建订单使用 `/api/order/order`，确认支付使用 `/api/payment/confirm/{orderNo}`。请求应带 `Authorization: Bearer <token>`；订单服务不信任客户端自报的角色 Header。

| 接口（相对后台路径） | 角色 | 关键约束 |
| --- | --- | --- |
| `GET /admin/orders` | `ORDER_OPERATOR`、`FINANCE_VIEWER`、`INVENTORY_VIEWER` | 必填 UTC `createdFrom`、`createdTo`，不超过 90 天；`limit` 为 1–100；同条件使用 `nextCursor` |
| `POST /admin/orders/exports` | `FINANCE_VIEWER` | `202`，冻结创建时读模型与财务字段集 |
| `GET /admin/orders/exports/{id}`、`/download`、`POST /{id}/retry` | 原创建者且有 `FINANCE_VIEWER` | 下载只允许 `READY`；完成 24 小时后过期 |
| `POST /admin/orders/rebuilds`、`GET /{id}`、`POST /{id}/retry` | `ORDER_OPERATOR` | 异步回填；失败重跑保留原水位与游标，不改订单/支付事实 |

运营视图保留金额与支付字段，但客户 ID 脱敏；财务视图可见完整客户 ID；库存视图不含金额、支付字段，返回 `inventorySnapshot`（可用或降级）。支付投影尚未知时 `paymentStatus` 为空，不应解释为未支付。游标仅保证读模型状态稳定时不重复、不遗漏；并发投影期间不构成跨请求快照。

回填遇到缺失订单号、支付单号、状态、版本或更新时间的历史支付事实时，跳过该条事实并增加任务 `failureCount`，记录 `REBUILD_FACT_SKIPPED` 审计；有效事实仍继续回放。`failureCount` 同时累计失败执行次数与跳过的事实数；`COMPLETED` 且该值大于零时，须结合审计区分历史执行故障和源数据问题，不能理解为全量无损恢复。服务、数据库或接口故障仍使任务进入 `FAILED`，由运营管理员受控重跑。

## 端到端验收

仅在隔离测试环境运行。准备一个可创建订单的普通用户令牌、分别拥有三类后台角色的令牌，以及库存充足的专用 `TEST_PRODUCT_ID`；四个令牌不要写入脚本、日志或文档。脚本会创建两笔真实订单、确认一笔支付、创建导出与回填任务，消耗两件库存，不能针对生产或共享数据直接运行。

```bash
export CUSTOMER_TOKEN='...'
export ORDER_OPERATOR_TOKEN='...'
export FINANCE_VIEWER_TOKEN='...'
export INVENTORY_VIEWER_TOKEN='...'
export TEST_PRODUCT_ID='p1'
E2E_WRITE=1 bash sh/test/test-order-admin-search.sh
```

可用 `API_BASE_URL`、`ADMIN_BASE_URL`、`PERF_BASE_URL` 指向隔离环境；默认分别为 `http://localhost:8080`、`http://localhost:8080/order-service`、`http://localhost:8081`。脚本在任何写入前检查网关和订单服务健康。它验收订单/支付事件投影、三角色字段、库存快照、无令牌拒绝、两页游标、CSV 完成及下载、回填完成和订单事实不变。业务等待窗口分别为投影 60 秒、导出 60 秒、回填 120 秒；这些只是脚本超时，不是服务 SLA。

脚本最后记录 20 次直连订单服务的检索 `p50/p95/max`。仓库当前没有为订单后台检索定义样本规模、并发数或合格阈值，因此此项只能形成可复现样本，不能宣称满足性能基线。正式性能验收需先确定数据量、筛选分布、并发、预热、硬件规格及 p95/错误率目标，再在代表性数据上重复测量。可靠性验收还应在隔离环境注入库存不可用、RabbitMQ 转发失败、投影重试耗尽、导出失败和回填中断，观察降级、死信、审计与受控恢复；不能在共享环境随意注入故障。

## 监控与故障恢复

观察 `admin.order.projection.events.total`、`admin.order.payment.projection.lag.ms`、`admin.order.inventory.degraded.total`、`admin.order.export.tasks`、`admin.order.rebuild.tasks` 和 `outbox.payment.*`，结合 `t_order_admin_activity_audit` 追踪操作。投影失败最多重试 5 次，之后转入 `q.order.admin.projection.dead` 或 `q.order.admin.payment.projection.dead`；无效消息直接进入死信。死信原消息需保留供排查。修复依赖后由 `ORDER_OPERATOR` 发起只读事实回填，失败任务可受控重跑；核对事件 ID 和读模型状态后再单独处置死信。导出失败由原创建者持 `FINANCE_VIEWER` 重试。不要从后台接口修改订单或支付事实，也不要把用户令牌透传给支付服务的内部回填读取接口。

## 2026-09-17 本地验收记录

- 六个受影响模块的 `mvn -q -f distributed/<module>/pom.xml test` 均返回 0：gateway 5、user-service 2、order-service 58、payment-service 3、inventory-service 2、outbox-consumer 1，共 71 项通过。这是模块级测试，不等同跨服务端到端通过。
- `bash -n sh/test/test-order-admin-search.sh` 返回 0。脚本预检在网关 `localhost:8080` 连接失败处停止，未创建业务数据。
- Docker 基础设施中的 MySQL、Nacos、RabbitMQ、Redis、Seata 正在运行；现有 `order_db` 读模型仅 5 行，缺少导出任务表及金额/支付时间排序索引。未对现有数据库应用迁移。
- 因网关未运行、导出迁移未应用、缺少专用四类测试令牌及代表性数据，订单至支付、库存、导出、回填的真实 E2E 和性能/故障注入基线**未通过验收、仍待运行**。

### 同日续验结果

在本机补齐缺失的 01、05、06 迁移，并为旧订单分片补 09 迁移后，六个服务健康检查均返回 200。通过专用本地账号运行 `E2E_WRITE=1 TEST_PRODUCT_ID=p1 bash sh/test/test-order-admin-search.sh`，订单/支付投影、字段权限、库存补充、游标、CSV 导出下载、受控回填和订单/支付事实前后不变检查全部通过。验收订单为 `O94001`、`O94002`；导出任务 `READY` 且快照行数为 1；回填任务 `COMPLETED`，处理 52 条、成功 51 条、跳过 1 条历史无效支付事实，并有对应审计。网关已认证请求访问支付内部回填路径返回 403。

启动与回填修复后，重新运行 `mvn -q -f distributed/order-service/pom.xml test`（59 项）及 `mvn -q -f distributed/payment-service/pom.xml test`（3 项）均通过；连同其他四个模块，当前共 72 项模块测试通过。

20 次直连检索样本为 p50 10ms、p95 19ms、最大 21ms；当时读模型仅 38 行，不能据此宣称满足正式性能基线。库存服务停机时列表仍成功返回、快照降级为 `UNAVAILABLE`、降级计数增加 1；恢复后重新为 `AVAILABLE`。未在本地执行 RabbitMQ 故障注入或死信清理。联调留下 4 个专用测试账号及 4 笔订单和关联任务/审计，`p1` 库存从 95 降至 91；未清理或回滚这些本地测试数据。

### 03、07、08 复验与历史数据清理

复验补充了支付投影乱序/重复/未知状态、财务导出下载鉴权与 24 小时到期边界、回填失败重试的水位/游标/审计等测试。`mvn -q -f distributed/order-service/pom.xml test` 现为 64 项全部通过。清理前确认 `payment_db.t_payment_1.id=14`（`pay_no=P29003`）是唯一空 `order_no` 支付事实，对应 `payment_db.t_outbox.id=3` 是 `DEAD` 事件。两条记录分别备份到 `payment_db.bak_empty_order_no_payment_20260917` 和 `payment_db.bak_empty_order_no_outbox_20260917` 后精确删除；支付事实三个表中空或纯空格 `order_no` 复查为 0。清理后新回填任务 `ce07b940-e498-40f4-8a6f-a23ec9b8a7d9` 已完成，处理 51 条、成功 51 条、失败 0 条，审计含 `CREATED` 与 `COMPLETED`。
