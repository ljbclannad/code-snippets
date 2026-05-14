# 数据库初始化脚本说明

## 执行顺序

1. **init-mysql.sql** - 基础数据库和表结构（订单、支付、库存、用户）
2. **mysql-schema.sql** - Nacos 配置中心表结构
3. **05-ai-memory-schema.sql** - AI 记忆功能表结构
4. **06-user-profile-schema.sql** - 用户画像服务表结构

## 快速初始化

```bash
# 连接到 MySQL
mysql -h localhost -P 3306 -u root -p

# 执行初始化脚本
source sql/init-mysql.sql
source sql/mysql-schema.sql
source sql/05-ai-memory-schema.sql
source sql/06-user-profile-schema.sql
```

## 各服务所需数据库

### 核心服务
- **order-service**: order_db (t_order_0, t_order_1, t_outbox, etc.)
- **payment-service**: payment_db (t_payment_0, t_payment_1)
- **inventory-service**: inventory_db (t_stock)
- **user-service**: user_db (t_user_0, t_user_1, t_user_account)

### AI 服务
- **ai-chat-service**: 无需数据库（仅使用 RabbitMQ 和 Redis）
- **user-profile-service**: governance (t_customer, t_health_assessment, b_ai_user_memory)
- **vector-memory-service**: governance (b_ai_group_memory, b_ai_user_memory, b_ai_chat_history) + Milvus

### 基础设施
- **Nacos**: nacos (config_info, etc.)
- **Seata**: seata (global_table, branch_table, lock_table)
