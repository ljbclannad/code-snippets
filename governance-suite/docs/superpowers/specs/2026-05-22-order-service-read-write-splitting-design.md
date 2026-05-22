# Order Service 读写分离设计方案

## 1. 概述

为 order-service 添加 MySQL 读写分离能力，在现有分表逻辑基础上，通过主从复制和 ShardingSphere 读写分离规则，实现读写流量分离和读库负载均衡。

### 目标

- 降低主库读压力，提升系统吞吐量
- 保持与现有分表逻辑的兼容性
- 保证 Seata 分布式事务的一致性
- 提供完整的学习和验证方案

### 范围

- **包含**：order-service 的读写分离配置
- **包含**：MySQL 一主两从架构
- **包含**：ShardingSphere 读写分离 + 分表组合配置
- **不包含**：其他服务（payment、user、inventory 等）
- **不包含**：主库故障自动切换（MHA/Orchestrator）

## 2. 架构设计

### 2.1 架构拓扑

```
order-service (8081)
    ↓
ShardingSphere JDBC
    ↓
┌─────────────────────────────────────┐
│  读写分离 + 分表组合                  │
├─────────────────────────────────────┤
│  写操作 → master (3306)              │
│           ├→ t_order_0               │
│           └→ t_order_1               │
│                                      │
│  读操作 → slave1 (3307) ─┐          │
│           slave2 (3308) ─┤ 轮询      │
│           ├→ t_order_0   │          │
│           └→ t_order_1   │          │
└─────────────────────────────────────┘
```

### 2.2 数据流向

#### 写操作（INSERT/UPDATE/DELETE）
1. ShardingSphere 路由到 master (3306)
2. 按 `order_no` hash 分片到 `t_order_0` 或 `t_order_1`
3. MySQL binlog 自动同步到 slave1、slave2

#### 读操作（SELECT）
1. ShardingSphere 轮询路由到 slave1 (3307) 或 slave2 (3308)
2. 按 `order_no` hash 分片到 `t_order_0` 或 `t_order_1`
3. 如果从库不可用，自动降级到主库

#### 事务内读操作
1. `@Transactional` 或 Seata 全局事务内的所有操作
2. 强制路由到主库（保证一致性）
3. 无需代码改动，ShardingSphere 自动识别事务上下文

### 2.3 组件职责

| 组件 | 职责 |
|------|------|
| **master (3306)** | 处理所有写操作，binlog 同步到从库 |
| **slave1 (3307)** | 处理读操作，从主库同步数据 |
| **slave2 (3308)** | 处理读操作，从主库同步数据 |
| **ShardingSphere** | 读写路由 + 分片路由 + 负载均衡 |

## 3. MySQL 主从复制设计

### 3.1 主从复制原理

```
Master (3306)
    ↓ binlog (ROW格式)
    ├→ Slave1 (3307) - IO Thread → Relay Log → SQL Thread → 应用到本地
    └→ Slave2 (3308) - IO Thread → Relay Log → SQL Thread → 应用到本地
```

### 3.2 主库配置

**关键参数**：
```ini
server-id=1                    # 唯一标识
log-bin=mysql-bin              # 开启 binlog
binlog-format=ROW              # ROW 格式（推荐）
expire_logs_days=7             # binlog 保留 7 天
max_binlog_size=100M           # 单个 binlog 文件大小
```

**复制用户**：
```sql
CREATE USER 'repl'@'%' IDENTIFIED BY 'repl123';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
FLUSH PRIVILEGES;
```

### 3.3 从库配置

**slave1 参数**：
```ini
server-id=2                    # 唯一标识
relay-log=mysql-relay-bin      # relay log 文件名
read_only=1                    # 只读模式（防止误写）
```

**slave2 参数**：
```ini
server-id=3                    # 唯一标识
relay-log=mysql-relay-bin      # relay log 文件名
read_only=1                    # 只读模式（防止误写）
```

**复制配置**：
```sql
CHANGE MASTER TO
  MASTER_HOST='mysql',
  MASTER_PORT=3306,
  MASTER_USER='repl',
  MASTER_PASSWORD='repl123',
  MASTER_AUTO_POSITION=1;       # 使用 GTID（推荐）

START SLAVE;
```

### 3.4 主从延迟

**正常情况**：
- 延迟 < 1s（局域网环境）
- 监控指标：`Seconds_Behind_Master`

**处理策略**：
- 事务内读操作强制走主库（强一致性）
- 非事务读操作容忍最终一致性
- 业务层可使用 hint 强制走主库

## 4. ShardingSphere 配置设计

### 4.1 配置结构

```yaml
spring:
  shardingsphere:
    datasource:
      names: master,slave1,slave2
      
      master:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/order_db?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
        username: ${MYSQL_USER:root}
        password: ${MYSQL_PASS:root}
        hikari:
          maximum-pool-size: 20
          minimum-idle: 5
          connection-timeout: 30000
      
      slave1:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://${MYSQL_HOST:localhost}:3307/order_db?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
        username: ${MYSQL_USER:root}
        password: ${MYSQL_PASS:root}
        hikari:
          maximum-pool-size: 50
          minimum-idle: 10
          connection-timeout: 30000
      
      slave2:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://${MYSQL_HOST:localhost}:3308/order_db?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
        username: ${MYSQL_USER:root}
        password: ${MYSQL_PASS:root}
        hikari:
          maximum-pool-size: 50
          minimum-idle: 10
          connection-timeout: 30000
    
    rules:
      # 读写分离规则
      - !READWRITE_SPLITTING
        dataSources:
          order_ds:
            writeDataSourceName: master
            readDataSourceNames:
              - slave1
              - slave2
            loadBalancerName: round_robin
        loadBalancers:
          round_robin:
            type: ROUND_ROBIN
      
      # 分表规则（基于逻辑数据源）
      - !SHARDING
        tables:
          t_order:
            actualDataNodes: order_ds.t_order_${0..1}
            tableStrategy:
              standard:
                shardingColumn: order_no
                shardingAlgorithmName: order-inline
          t_outbox:
            actualDataNodes: order_ds.t_outbox
          t_seckill_order_guard:
            actualDataNodes: order_ds.t_seckill_order_guard
        shardingAlgorithms:
          order-inline:
            type: INLINE
            props:
              algorithm-expression: t_order_${Math.abs(order_no.hashCode()) % 2}
    
    props:
      sql-show: true
```

### 4.2 关键设计点

#### 逻辑数据源
- `order_ds` 封装了 master + slave1 + slave2
- 分表规则引用逻辑数据源，而不是物理数据源
- ShardingSphere 自动处理读写路由 + 分片路由

#### 读写路由优先级
1. 先判断操作类型（读/写）
2. 再根据分片键路由到具体表
3. 最后选择具体的物理数据源

#### 负载均衡策略
- **ROUND_ROBIN**：轮询，平均分配到各个从库
- 适合从库性能相同的场景
- 其他可选：RANDOM（随机）、WEIGHT（权重）

#### 事务处理
- `@Transactional` 内的所有操作强制走主库
- Seata 全局事务自动走主库
- 无需额外配置，ShardingSphere 自动识别

#### 降级策略
- 从库不可用时自动降级到主库
- 应用无感知，继续提供服务
- 日志会记录降级事件

### 4.3 连接池配置

| 数据源 | 最大连接数 | 最小空闲 | 说明 |
|--------|-----------|---------|------|
| master | 20 | 5 | 写操作较少 |
| slave1 | 50 | 10 | 读操作较多 |
| slave2 | 50 | 10 | 读操作较多 |

## 5. Docker Compose 配置

### 5.1 服务定义

```yaml
services:
  mysql:
    image: example/mysql:8.0.30
    container_name: gov-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: root
      TZ: Asia/Shanghai
    ports:
      - "3306:3306"
    volumes:
      - ./sql:/docker-entrypoint-initdb.d:ro
      - gov_mysql_data:/var/lib/mysql
      - ./docker/mysql/master.cnf:/etc/mysql/conf.d/master.cnf:ro
    command:
      - "--character-set-server=utf8mb4"
      - "--collation-server=utf8mb4_unicode_ci"
      - "--default-time-zone=+08:00"
    networks:
      - gov-network

  mysql-slave1:
    image: example/mysql:8.0.30
    container_name: gov-mysql-slave1
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: root
      TZ: Asia/Shanghai
    ports:
      - "3307:3306"
    volumes:
      - gov_mysql_slave1_data:/var/lib/mysql
      - ./docker/mysql/slave1.cnf:/etc/mysql/conf.d/slave.cnf:ro
    command:
      - "--character-set-server=utf8mb4"
      - "--collation-server=utf8mb4_unicode_ci"
      - "--default-time-zone=+08:00"
    depends_on:
      - mysql
    networks:
      - gov-network

  mysql-slave2:
    image: example/mysql:8.0.30
    container_name: gov-mysql-slave2
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: root
      TZ: Asia/Shanghai
    ports:
      - "3308:3306"
    volumes:
      - gov_mysql_slave2_data:/var/lib/mysql
      - ./docker/mysql/slave2.cnf:/etc/mysql/conf.d/slave.cnf:ro
    command:
      - "--character-set-server=utf8mb4"
      - "--collation-server=utf8mb4_unicode_ci"
      - "--default-time-zone=+08:00"
    depends_on:
      - mysql
    networks:
      - gov-network

volumes:
  gov_mysql_data:
  gov_mysql_slave1_data:
  gov_mysql_slave2_data:

networks:
  gov-network:
    driver: bridge
```

### 5.2 配置文件

**docker/mysql/master.cnf**：
```ini
[mysqld]
server-id=1
log-bin=mysql-bin
binlog-format=ROW
expire_logs_days=7
max_binlog_size=100M
gtid_mode=ON
enforce_gtid_consistency=ON
```

**docker/mysql/slave1.cnf**：
```ini
[mysqld]
server-id=2
relay-log=mysql-relay-bin
read_only=1
gtid_mode=ON
enforce_gtid_consistency=ON
```

**docker/mysql/slave2.cnf**：
```ini
[mysqld]
server-id=3
relay-log=mysql-relay-bin
read_only=1
gtid_mode=ON
enforce_gtid_consistency=ON
```

## 6. 实施步骤

### 步骤 1：准备配置文件

```bash
# 创建配置目录
mkdir -p docker/mysql

# 创建主库配置
cat > docker/mysql/master.cnf << 'EOF'
[mysqld]
server-id=1
log-bin=mysql-bin
binlog-format=ROW
expire_logs_days=7
max_binlog_size=100M
gtid_mode=ON
enforce_gtid_consistency=ON
EOF

# 创建从库配置
cat > docker/mysql/slave1.cnf << 'EOF'
[mysqld]
server-id=2
relay-log=mysql-relay-bin
read_only=1
gtid_mode=ON
enforce_gtid_consistency=ON
EOF

cat > docker/mysql/slave2.cnf << 'EOF'
[mysqld]
server-id=3
relay-log=mysql-relay-bin
read_only=1
gtid_mode=ON
enforce_gtid_consistency=ON
EOF
```

### 步骤 2：更新 docker-compose.yml

添加 mysql-slave1 和 mysql-slave2 服务定义（见第 5 节）。

### 步骤 3：启动 MySQL 集群

```bash
# 停止现有服务
docker compose down

# 启动新集群
docker compose up -d mysql mysql-slave1 mysql-slave2

# 等待启动完成
sleep 10
```

### 步骤 4：配置主从复制

```bash
# 在主库创建复制用户
docker exec -it gov-mysql mysql -uroot -proot -e "
CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED BY 'repl123';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
FLUSH PRIVILEGES;
"

# 配置 slave1
docker exec -it gov-mysql-slave1 mysql -uroot -proot -e "
CHANGE MASTER TO
  MASTER_HOST='mysql',
  MASTER_PORT=3306,
  MASTER_USER='repl',
  MASTER_PASSWORD='repl123',
  MASTER_AUTO_POSITION=1;
START SLAVE;
"

# 配置 slave2
docker exec -it gov-mysql-slave2 mysql -uroot -proot -e "
CHANGE MASTER TO
  MASTER_HOST='mysql',
  MASTER_PORT=3306,
  MASTER_USER='repl',
  MASTER_PASSWORD='repl123',
  MASTER_AUTO_POSITION=1;
START SLAVE;
"
```

### 步骤 5：验证主从复制

```bash
# 检查 slave1 状态
docker exec -it gov-mysql-slave1 mysql -uroot -proot -e "SHOW SLAVE STATUS\G" | grep -E "Slave_IO_Running|Slave_SQL_Running|Seconds_Behind_Master"

# 检查 slave2 状态
docker exec -it gov-mysql-slave2 mysql -uroot -proot -e "SHOW SLAVE STATUS\G" | grep -E "Slave_IO_Running|Slave_SQL_Running|Seconds_Behind_Master"

# 期望输出：
# Slave_IO_Running: Yes
# Slave_SQL_Running: Yes
# Seconds_Behind_Master: 0
```

### 步骤 6：更新 order-service 配置

修改 `order-service/src/main/resources/application.yml`（见第 4.1 节）。

### 步骤 7：重启 order-service

```bash
# 重新编译
mvn -q -pl order-service clean package -DskipTests

# 启动服务
mvn -q -pl order-service spring-boot:run
```

## 7. 验证方案

### 7.1 主从复制验证

```bash
# 在主库写入数据
docker exec -it gov-mysql mysql -uroot -proot order_db -e "
INSERT INTO t_order_0 (order_no, user_id, product_id, quantity, amount_cents, status, created_at, updated_at)
VALUES ('test-rw-001', 1001, 'p1', 1, 100, 'PENDING', NOW(), NOW());"

# 在 slave1 查询
docker exec -it gov-mysql-slave1 mysql -uroot -proot order_db -e "
SELECT order_no, user_id, status FROM t_order_0 WHERE order_no='test-rw-001';"

# 在 slave2 查询
docker exec -it gov-mysql-slave2 mysql -uroot -proot order_db -e "
SELECT order_no, user_id, status FROM t_order_0 WHERE order_no='test-rw-001';"

# 应该都能查到数据
```

### 7.2 读写分离验证

```bash
# 获取 Token
TOKEN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456"}' | jq -r '.data.token')

# 执行查询操作（观察日志）
curl "http://localhost:8080/api/order/order/test-rw-001" \
  -H "Authorization: Bearer $TOKEN"

# 日志应显示：Actual SQL: slave1 ::: SELECT ... 或 slave2 ::: SELECT ...

# 执行写操作（观察日志）
curl -X POST http://localhost:8080/api/order/order \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-rw-002" \
  -d '{"productId":"p1","quantity":1,"amountCents":100}'

# 日志应显示：Actual SQL: master ::: INSERT ...
```

### 7.3 负载均衡验证

```bash
# 连续执行 10 次查询
for i in {1..10}; do
  curl -s "http://localhost:8080/api/order/order/test-rw-001" \
    -H "Authorization: Bearer $TOKEN" | jq -r '.traceId'
  sleep 0.5
done

# 观察 order-service 日志
# slave1 和 slave2 应该交替出现（轮询）
```

### 7.4 事务一致性验证

```bash
# 执行 Seata 分布式事务（下单）
curl -X POST http://localhost:8080/api/order/order \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-seata-001" \
  -d '{"productId":"p1","quantity":1,"amountCents":100}'

# 观察日志，事务内的所有读写操作都应该在 master
# 不会出现 slave1 或 slave2
```

### 7.5 从库故障验证

```bash
# 停止 slave1
docker stop gov-mysql-slave1

# 执行查询（应该自动降级到 master 或 slave2）
for i in {1..5}; do
  curl -s "http://localhost:8080/api/order/order/test-rw-001" \
    -H "Authorization: Bearer $TOKEN" | jq -r '.success'
done

# 应该全部返回 true（无感知）

# 恢复 slave1
docker start gov-mysql-slave1
```

## 8. 监控与运维

### 8.1 监控指标

**MySQL 主从监控**：
```sql
-- 在从库执行
SHOW SLAVE STATUS\G

-- 关键指标：
-- Slave_IO_Running: Yes（IO 线程正常）
-- Slave_SQL_Running: Yes（SQL 线程正常）
-- Seconds_Behind_Master: 0（主从延迟）
-- Last_IO_Error: （无 IO 错误）
-- Last_SQL_Error: （无 SQL 错误）
```

**应用层监控**：
- ShardingSphere SQL 日志（数据源路由）
- HikariCP 连接池指标
- 慢查询日志（从库性能）

### 8.2 故障场景

#### 场景 1：从库宕机
- **现象**：slave1 或 slave2 不可用
- **影响**：读流量自动降级到主库或其他从库
- **恢复**：重启从库，自动恢复复制

#### 场景 2：主从延迟过大
- **现象**：`Seconds_Behind_Master` > 5s
- **原因**：主库写入压力大、网络延迟、从库性能不足
- **影响**：非事务读操作可能读到旧数据
- **处理**：
  - 事务内读操作自动走主库（无影响）
  - 优化主库写入性能
  - 升级从库硬件

#### 场景 3：主库宕机
- **现象**：master 不可用
- **影响**：写操作失败，读操作正常
- **恢复**：手动提升从库为主库（本方案不包含自动故障转移）

### 8.3 日常运维

**检查复制状态**：
```bash
# 每日检查脚本
docker exec -it gov-mysql-slave1 mysql -uroot -proot -e "SHOW SLAVE STATUS\G" | grep -E "Slave_IO_Running|Slave_SQL_Running|Seconds_Behind_Master"
docker exec -it gov-mysql-slave2 mysql -uroot -proot -e "SHOW SLAVE STATUS\G" | grep -E "Slave_IO_Running|Slave_SQL_Running|Seconds_Behind_Master"
```

**重置复制**（如果复制中断）：
```bash
# 在从库执行
docker exec -it gov-mysql-slave1 mysql -uroot -proot -e "
STOP SLAVE;
RESET SLAVE;
CHANGE MASTER TO
  MASTER_HOST='mysql',
  MASTER_PORT=3306,
  MASTER_USER='repl',
  MASTER_PASSWORD='repl123',
  MASTER_AUTO_POSITION=1;
START SLAVE;
"
```

## 9. 注意事项

### 9.1 数据一致性

**主从延迟**：
- 正常情况下延迟 < 1s
- 业务需要容忍最终一致性
- 强一致性场景使用 `@Transactional` 强制走主库

**Hint 强制路由**：
```java
// 强制走主库
/* ShardingSphere hint:useWriteDataSource=true */ SELECT ...

// 强制走从库
/* ShardingSphere hint:useWriteDataSource=false */ SELECT ...
```

### 9.2 Seata 兼容性

- Seata 全局事务会自动识别事务上下文
- 事务内的所有操作（包括读）都会路由到主库
- 无需额外配置，ShardingSphere 自动处理

### 9.3 连接池配置

**主库连接池**：
- 写操作较少，连接数不需要太多
- `maximum-pool-size: 20`

**从库连接池**：
- 读操作较多，需要更多连接
- `maximum-pool-size: 50`
- 根据实际压测调整

### 9.4 初始数据同步

**空库场景**（本项目）：
- 主库和从库都是空库
- 直接配置主从复制即可

**已有数据场景**：
```bash
# 1. 导出主库数据（带 binlog 位置）
mysqldump -h127.0.0.1 -P3306 -uroot -proot \
  --master-data=2 --single-transaction \
  order_db > order_db.sql

# 2. 导入从库
mysql -h127.0.0.1 -P3307 -uroot -proot order_db < order_db.sql

# 3. 配置主从复制（使用导出时的 binlog 位置）
```

### 9.5 学习价值

通过这个方案，可以学习到：
- ✅ MySQL 主从复制原理（binlog、relay log、GTID）
- ✅ 读写分离的权衡（性能 vs 一致性）
- ✅ ShardingSphere 的组合能力（读写分离 + 分表）
- ✅ 负载均衡策略（轮询、随机、权重）
- ✅ 事务一致性保证（事务内强制走主库）
- ✅ 故障降级机制（从库不可用自动降级）

## 10. 不包含的内容（可后续扩展）

### 10.1 主库故障自动切换
- 需要 MHA（Master High Availability）或 Orchestrator
- 自动检测主库故障并提升从库为主库
- 需要 VIP（虚拟 IP）或 DNS 切换

### 10.2 多主复制
- MySQL Group Replication（MGR）
- 多个节点都可以写入
- 自动冲突检测和解决

### 10.3 跨机房部署
- 需要考虑网络延迟
- 半同步复制（semi-sync replication）
- 就近读取策略

### 10.4 读写分离的动态配置
- 通过配置中心（Nacos）动态调整
- 无需重启服务
- 支持灰度发布

## 11. 总结

这个方案为 order-service 添加了完整的读写分离能力：

- ✅ **1 主 2 从的 MySQL 架构**：真实的主从复制环境
- ✅ **基于 binlog 的主从复制**：自动数据同步
- ✅ **ShardingSphere 读写分离**：轮询负载均衡
- ✅ **与现有分表逻辑无缝集成**：读写分离 + 分表同时生效
- ✅ **事务内强制走主库**：保证一致性
- ✅ **从库故障自动降级**：高可用保障

通过这个方案，可以深入理解读写分离的原理、实现和权衡，为后续的生产环境部署打下基础。
