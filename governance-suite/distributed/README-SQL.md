# 数据库初始化说明

## 快速初始化

```bash
mysql -h127.0.0.1 -P3306 -uroot -proot < distributed/init-databases.sql
```

## 包含的数据库

### order_db
- **使用服务**: order-service, appointment-service, outbox-consumer
- **表结构**:
  - `t_order_0`, `t_order_1` - 订单表（分片）
  - `t_outbox` - Outbox 消息表
  - `t_order_admin_read_model` - 后台订单最终一致读模型
  - `t_order_admin_export_task`, `t_order_admin_export_row` - 财务导出任务与冻结快照
  - `t_order_admin_rebuild_task`, `t_order_admin_rebuild_audit` - 回填任务与操作记录
  - `t_order_admin_activity_audit` - 后台检索、导出、回填和事件失败审计
  - `t_order_tcc_log` - 订单TCC事务日志
  - `t_payment_event_consume` - 支付事件消费记录
  - `t_seckill_order_guard` - 秒杀订单防重表
  - `t_appointment_order` - 挂号订单表
  - `t_appointment_idempotency` - 挂号幂等表
  - `t_department` - 科室表
  - `t_doctor` - 医生表
  - `t_schedule` - 排班表
  - `t_quota_refund_log` - 号源退还日志
  - `t_id_segment` - ID段分配表
  - `undo_log` - Seata 回滚日志

### payment_db
- **使用服务**: payment-service
- **表结构**:
  - `t_payment_0`, `t_payment_1` - 支付表（分片）
  - `t_payment_tcc_log` - 支付TCC事务日志
  - `t_id_segment` - ID段分配表
  - `undo_log` - Seata 回滚日志

### inventory_db
- **使用服务**: inventory-service
- **表结构**:
  - `t_stock` - 库存表
  - `t_inventory_tcc_log` - 库存TCC事务日志
  - `undo_log` - Seata 回滚日志

### user_db
- **使用服务**: user-service
- **表结构**:
  - `t_user_0`, `t_user_1` - 用户表（分片）
  - `t_user_account` - 用户账户表
  - `t_id_segment` - ID段分配表

### seata
- **使用组件**: Seata Server
- **表结构**:
  - `global_table` - 全局事务表
  - `branch_table` - 分支事务表
  - `lock_table` - 全局锁表

## 测试数据

初始化脚本包含以下测试数据：

- **库存**: `p1`(1000), `p2`(500), `p3`(800)
- **科室**: 内科、外科、妇科、儿科
- **ID段**: order, appointment, payment, user

## 重建数据库

```bash
# 删除所有分布式系统数据库
mysql -h127.0.0.1 -P3306 -uroot -proot << 'EOF'
DROP DATABASE IF EXISTS order_db;
DROP DATABASE IF EXISTS payment_db;
DROP DATABASE IF EXISTS inventory_db;
DROP DATABASE IF EXISTS user_db;
DROP DATABASE IF EXISTS seata;
EOF

# 重新初始化
mysql -h127.0.0.1 -P3306 -uroot -proot < distributed/init-databases.sql
```

## 注意事项

已有环境的后台订单增量迁移顺序和验收前检查见 [订单后台检索联调验收与运维](README-ORDER-ADMIN-SEARCH.md)。迁移并非幂等，须先检查目标表、列和索引，不能重复执行。

1. **undo_log 表**: 每个参与 Seata 事务的数据库都必须包含 `undo_log` 表
2. **分片表**: `t_order`, `t_payment`, `t_user` 按哈希分片到 `_0` 和 `_1` 两个物理表
3. **ID 生成**: 使用号段模式，从 `t_id_segment` 表批量获取 ID
4. **乐观锁**: 涉及并发更新的表都包含 `version` 字段
