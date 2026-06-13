# 数据库初始化脚本说明

数据库初始化脚本已按项目结构重组：

## 文件组织

```
governance-suite/
├── mysql-schema.sql           # Nacos 配置中心表结构（基础设施）
├── distributed/
│   └── init-databases.sql     # 分布式系统数据库初始化
└── ai/
    └── init-databases.sql     # AI 服务数据库初始化
```

## 快速初始化

### 1. 初始化基础设施（Nacos）

```bash
mysql -h127.0.0.1 -P3306 -uroot -proot < mysql-schema.sql
```

### 2. 初始化分布式系统

```bash
mysql -h127.0.0.1 -P3306 -uroot -proot < distributed/init-databases.sql
```

包含数据库：
- `order_db` - 订单服务、Outbox、预约服务
- `payment_db` - 支付服务
- `inventory_db` - 库存服务
- `user_db` - 用户服务
- `seata` - Seata Server（分布式事务协调）

### 3. 初始化 AI 服务

```bash
mysql -h127.0.0.1 -P3306 -uroot -proot < ai/init-databases.sql
```

包含数据库：
- `governance` - 用户画像、向量记忆、知识库（共享）
- `doctor_assistant` - 医生助手 Agent

## 完整初始化（推荐）

```bash
# 一次性初始化所有数据库
mysql -h127.0.0.1 -P3306 -uroot -proot < mysql-schema.sql
mysql -h127.0.0.1 -P3306 -uroot -proot < distributed/init-databases.sql
mysql -h127.0.0.1 -P3306 -uroot -proot < ai/init-databases.sql
```

或使用 Docker Compose 自动初始化：

```bash
# Docker Compose 会自动执行 sql/ 目录下的脚本
docker compose down -v
docker compose up -d
```

## 数据库映射

### 分布式系统服务

| 服务                | 数据库       | 表                                                                                                                   |
| ------------------- | ------------ | -------------------------------------------------------------------------------------------------------------------- |
| order-service       | order_db     | t_order_0, t_order_1, t_outbox, t_payment_event_consume, t_seckill_order_guard, undo_log                             |
| payment-service     | payment_db   | t_payment_0, t_payment_1, t_id_segment, undo_log                                                                     |
| inventory-service   | inventory_db | t_stock, undo_log                                                                                                    |
| user-service        | user_db      | t_user_0, t_user_1, t_user_account, t_id_segment                                                                     |
| appointment-service | order_db     | t_appointment_order, t_appointment_idempotency, t_department, t_doctor, t_schedule, t_quota_refund_log, t_id_segment |
| outbox-consumer     | order_db     | t_outbox (只读)                                                                                                      |

### AI 服务

| 服务                   | 数据库           | 表                                                                                                           |
| ---------------------- | ---------------- | ------------------------------------------------------------------------------------------------------------ |
| user-profile-service   | governance       | t_customer, t_health_assessment, b_ai_user_memory, b_user_profile_*                                          |
| vector-memory-service  | governance       | b_ai_group_memory*, b_ai_chat_history, t_ai_chat_history                                                     |
| ai-chat-service        | -                | 无（仅使用 Redis/RabbitMQ/Elasticsearch）                                                                    |
| doctor-assistant-agent | doctor_assistant | agent_*, chat_message, follow_up_task, health_knowledge, patient_profile, prescription_record, weight_record |
| knowledge-base-service | governance       | knowledge_base, knowledge_document, knowledge_document_chunk, knowledge_document_parse_result                |

### 基础设施

| 组件  | 数据库 | 说明                   |
| ----- | ------ | ---------------------- |
| Nacos | nacos  | 配置中心和服务注册中心 |
| Seata | seata  | 分布式事务协调器       |

## 重建数据库

如果需要清空并重建：

```bash
# 方式 1: 使用 Docker Compose（推荐）
docker compose down -v
docker compose up -d

# 方式 2: 手动删除并重建
mysql -h127.0.0.1 -P3306 -uroot -proot -e "DROP DATABASE IF EXISTS order_db;"
mysql -h127.0.0.1 -P3306 -uroot -proot -e "DROP DATABASE IF EXISTS payment_db;"
mysql -h127.0.0.1 -P3306 -uroot -proot -e "DROP DATABASE IF EXISTS inventory_db;"
mysql -h127.0.0.1 -P3306 -uroot -proot -e "DROP DATABASE IF EXISTS user_db;"
mysql -h127.0.0.1 -P3306 -uroot -proot -e "DROP DATABASE IF EXISTS governance;"
mysql -h127.0.0.1 -P3306 -uroot -proot -e "DROP DATABASE IF EXISTS doctor_assistant;"
mysql -h127.0.0.1 -P3306 -uroot -proot -e "DROP DATABASE IF EXISTS seata;"
mysql -h127.0.0.1 -P3306 -uroot -proot -e "DROP DATABASE IF EXISTS nacos;"

# 然后重新执行初始化脚本
mysql -h127.0.0.1 -P3306 -uroot -proot < nacos-schema.sql
mysql -h127.0.0.1 -P3306 -uroot -proot < distributed/init-databases.sql
mysql -h127.0.0.1 -P3306 -uroot -proot < ai/init-databases.sql
```

## 注意事项

1. **Seata undo_log 表**：每个参与 Seata 分布式事务的数据库都需要 `undo_log` 表
2. **分片表**：`t_order_0/1`, `t_payment_0/1`, `t_user_0/1` 是 ShardingSphere 分片表
3. **ID 生成**：`t_id_segment` 表用于分布式 ID 生成（号段模式）
4. **测试数据**：初始化脚本包含少量测试数据（库存、科室等）

## 旧脚本（已废弃）

sql/ 目录下的旧脚本已废弃，请使用新的结构化脚本：

- ~~sql/init-mysql.sql~~ → `distributed/init-databases.sql`
- ~~sql/05-ai-memory-schema.sql~~ → `ai/init-databases.sql`
- ~~sql/06-user-profile-schema.sql~~ → `ai/init-databases.sql`
- ~~sql/init-appointment.sql~~ → 已合并到 `distributed/init-databases.sql`
- ~~sql/mysql-schema.sql~~ → `nacos-schema.sql`
