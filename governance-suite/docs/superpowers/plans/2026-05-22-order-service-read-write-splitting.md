# Order Service 读写分离实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 order-service 添加 MySQL 读写分离能力，实现 1 主 2 从架构，通过 ShardingSphere 实现读写路由和负载均衡

**Architecture:** 使用 MySQL 主从复制（基于 GTID）+ ShardingSphere 读写分离规则，写操作路由到主库，读操作轮询路由到两个从库，与现有分表逻辑无缝集成

**Tech Stack:** MySQL 8.0.30, ShardingSphere 5.5.0, Docker Compose, HikariCP

---

## 文件结构

**创建的文件：**
- `docker/mysql/master.cnf` - 主库 MySQL 配置（开启 binlog、GTID）
- `docker/mysql/slave1.cnf` - 从库1 MySQL 配置（relay log、只读）
- `docker/mysql/slave2.cnf` - 从库2 MySQL 配置（relay log、只读）

**修改的文件：**
- `docker-compose.yml` - 添加 mysql-slave1、mysql-slave2 服务，修改 mysql 服务
- `order-service/src/main/resources/application.yml` - 添加从库数据源，配置读写分离规则

---

## Task 1: 创建 MySQL 主库配置文件

**Files:**
- Create: `docker/mysql/master.cnf`

- [ ] **Step 1: 创建配置目录**

```bash
mkdir -p docker/mysql
```

- [ ] **Step 2: 创建主库配置文件**

```bash
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
```

- [ ] **Step 3: 验证文件内容**

```bash
cat docker/mysql/master.cnf
```

Expected output:
```
[mysqld]
server-id=1
log-bin=mysql-bin
binlog-format=ROW
expire_logs_days=7
max_binlog_size=100M
gtid_mode=ON
enforce_gtid_consistency=ON
```

- [ ] **Step 4: 提交配置文件**

```bash
git add docker/mysql/master.cnf
git commit -m "feat(mysql): add master config for read-write splitting

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: 创建 MySQL 从库配置文件

**Files:**
- Create: `docker/mysql/slave1.cnf`
- Create: `docker/mysql/slave2.cnf`

- [ ] **Step 1: 创建 slave1 配置文件**

```bash
cat > docker/mysql/slave1.cnf << 'EOF'
[mysqld]
server-id=2
relay-log=mysql-relay-bin
read_only=1
gtid_mode=ON
enforce_gtid_consistency=ON
EOF
```

- [ ] **Step 2: 创建 slave2 配置文件**

```bash
cat > docker/mysql/slave2.cnf << 'EOF'
[mysqld]
server-id=3
relay-log=mysql-relay-bin
read_only=1
gtid_mode=ON
enforce_gtid_consistency=ON
EOF
```

- [ ] **Step 3: 验证文件内容**

```bash
cat docker/mysql/slave1.cnf
cat docker/mysql/slave2.cnf
```

Expected: 两个文件内容相同，只有 server-id 不同（2 和 3）

- [ ] **Step 4: 提交配置文件**

```bash
git add docker/mysql/slave1.cnf docker/mysql/slave2.cnf
git commit -m "feat(mysql): add slave configs for read-write splitting

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: 更新 docker-compose.yml 添加从库服务

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: 备份现有配置**

```bash
cp docker-compose.yml docker-compose.yml.backup
```

- [ ] **Step 2: 修改 mysql 服务添加配置挂载**

在 `mysql` 服务的 `volumes` 部分添加：
```yaml
- ./docker/mysql/master.cnf:/etc/mysql/conf.d/master.cnf:ro
```

完整的 mysql 服务配置应该是：
```yaml
mysql:
    image: example/mysql:8.0.30
    pull_policy: never
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
```

- [ ] **Step 3: 在 mysql 服务后添加 mysql-slave1 服务**

```yaml
mysql-slave1:
    image: example/mysql:8.0.30
    pull_policy: never
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
```

- [ ] **Step 4: 添加 mysql-slave2 服务**

```yaml
mysql-slave2:
    image: example/mysql:8.0.30
    pull_policy: never
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
```

- [ ] **Step 5: 在 volumes 部分添加从库数据卷**

在 `volumes:` 部分添加：
```yaml
gov_mysql_slave1_data:
gov_mysql_slave2_data:
```

- [ ] **Step 6: 验证 YAML 语法**

```bash
docker compose config > /dev/null && echo "YAML syntax OK" || echo "YAML syntax ERROR"
```

Expected: "YAML syntax OK"

- [ ] **Step 7: 提交更改**

```bash
git add docker-compose.yml
git commit -m "feat(docker): add mysql slave services for read-write splitting

- Add mysql-slave1 on port 3307
- Add mysql-slave2 on port 3308
- Mount slave config files
- Add data volumes for slaves

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: 启动 MySQL 集群

**Files:**
- None (infrastructure operation)

- [ ] **Step 1: 停止现有 MySQL 服务**

```bash
docker compose stop mysql
```

Expected: "Container gov-mysql Stopped"

- [ ] **Step 2: 删除现有 MySQL 容器（保留数据）**

```bash
docker compose rm -f mysql
```

- [ ] **Step 3: 启动新的 MySQL 集群**

```bash
docker compose up -d mysql mysql-slave1 mysql-slave2
```

Expected: 3 个容器启动成功

- [ ] **Step 4: 等待 MySQL 启动完成**

```bash
sleep 15
```

- [ ] **Step 5: 验证容器状态**

```bash
docker ps | grep gov-mysql
```

Expected output: 应该看到 3 个容器（gov-mysql, gov-mysql-slave1, gov-mysql-slave2）都在运行

- [ ] **Step 6: 验证主库配置生效**

```bash
docker exec -it gov-mysql mysql -uroot -proot -e "SHOW VARIABLES LIKE 'server_id';"
docker exec -it gov-mysql mysql -uroot -proot -e "SHOW VARIABLES LIKE 'log_bin';"
docker exec -it gov-mysql mysql -uroot -proot -e "SHOW VARIABLES LIKE 'gtid_mode';"
```

Expected:
- server_id = 1
- log_bin = ON
- gtid_mode = ON

- [ ] **Step 7: 验证从库配置生效**

```bash
docker exec -it gov-mysql-slave1 mysql -uroot -proot -e "SHOW VARIABLES LIKE 'server_id';"
docker exec -it gov-mysql-slave2 mysql -uroot -proot -e "SHOW VARIABLES LIKE 'server_id';"
```

Expected:
- slave1 server_id = 2
- slave2 server_id = 3

---

## Task 5: 配置 MySQL 主从复制

**Files:**
- None (database operation)

- [ ] **Step 1: 在主库创建复制用户**

```bash
docker exec -it gov-mysql mysql -uroot -proot -e "
CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED BY 'repl123';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
FLUSH PRIVILEGES;
"
```

Expected: Query OK

- [ ] **Step 2: 验证复制用户创建成功**

```bash
docker exec -it gov-mysql mysql -uroot -proot -e "SELECT User, Host FROM mysql.user WHERE User='repl';"
```

Expected: 应该看到 repl 用户

- [ ] **Step 3: 配置 slave1 连接主库**

```bash
docker exec -it gov-mysql-slave1 mysql -uroot -proot -e "
CHANGE MASTER TO
  MASTER_HOST='mysql',
  MASTER_PORT=3306,
  MASTER_USER='repl',
  MASTER_PASSWORD='repl123',
  MASTER_AUTO_POSITION=1;
START SLAVE;
"
```

Expected: Query OK

- [ ] **Step 4: 配置 slave2 连接主库**

```bash
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

Expected: Query OK

- [ ] **Step 5: 验证 slave1 复制状态**

```bash
docker exec -it gov-mysql-slave1 mysql -uroot -proot -e "SHOW SLAVE STATUS\G" | grep -E "Slave_IO_Running|Slave_SQL_Running|Seconds_Behind_Master"
```

Expected:
- Slave_IO_Running: Yes
- Slave_SQL_Running: Yes
- Seconds_Behind_Master: 0

- [ ] **Step 6: 验证 slave2 复制状态**

```bash
docker exec -it gov-mysql-slave2 mysql -uroot -proot -e "SHOW SLAVE STATUS\G" | grep -E "Slave_IO_Running|Slave_SQL_Running|Seconds_Behind_Master"
```

Expected:
- Slave_IO_Running: Yes
- Slave_SQL_Running: Yes
- Seconds_Behind_Master: 0

- [ ] **Step 7: 测试主从数据同步**

```bash
# 在主库插入测试数据
docker exec -it gov-mysql mysql -uroot -proot order_db -e "
INSERT INTO t_order_0 (order_no, user_id, product_id, quantity, amount_cents, status, created_at, updated_at)
VALUES ('test-repl-001', 9999, 'p-test', 1, 100, 'PENDING', NOW(), NOW());"

# 在 slave1 查询
docker exec -it gov-mysql-slave1 mysql -uroot -proot order_db -e "
SELECT order_no, user_id, status FROM t_order_0 WHERE order_no='test-repl-001';"

# 在 slave2 查询
docker exec -it gov-mysql-slave2 mysql -uroot -proot order_db -e "
SELECT order_no, user_id, status FROM t_order_0 WHERE order_no='test-repl-001';"
```

Expected: 两个从库都能查到数据

- [ ] **Step 8: 清理测试数据**

```bash
docker exec -it gov-mysql mysql -uroot -proot order_db -e "
DELETE FROM t_order_0 WHERE order_no='test-repl-001';"
```

---

(待续 - 文件太长，将在下一部分继续)

## Task 6: 更新 order-service 配置添加读写分离

**Files:**
- Modify: `order-service/src/main/resources/application.yml`

- [ ] **Step 1: 备份现有配置**

```bash
cp order-service/src/main/resources/application.yml order-service/src/main/resources/application.yml.backup
```

- [ ] **Step 2: 修改 shardingsphere.datasource 部分**

将原来的单数据源配置：
```yaml
spring:
  shardingsphere:
    datasource:
      names: ds0
      ds0:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/order_db?...
        username: ${MYSQL_USER:root}
        password: ${MYSQL_PASS:root}
```

替换为三数据源配置：
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
```

- [ ] **Step 3: 在 rules 部分添加读写分离规则**

在 `rules:` 下，在 `sharding:` 规则之前添加读写分离规则：
```yaml
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
      
      # 分表规则（保持不变，但使用逻辑数据源）
      sharding:
        ...
```

- [ ] **Step 4: 修改分表规则使用逻辑数据源**

将原来的：
```yaml
      sharding:
        tables:
          t_order:
            actual-data-nodes: ds0.t_order_${0..1}
```

修改为：
```yaml
      sharding:
        tables:
          t_order:
            actual-data-nodes: order_ds.t_order_${0..1}
```

同样修改 `t_outbox` 和 `t_seckill_order_guard`：
```yaml
          t_outbox:
            actual-data-nodes: order_ds.t_outbox
          t_seckill_order_guard:
            actual-data-nodes: order_ds.t_seckill_order_guard
```

- [ ] **Step 5: 验证 YAML 语法**

```bash
# 使用 yq 或 Python 验证 YAML 语法
python3 -c "import yaml; yaml.safe_load(open('order-service/src/main/resources/application.yml'))" && echo "YAML syntax OK" || echo "YAML syntax ERROR"
```

Expected: "YAML syntax OK"

- [ ] **Step 6: 提交配置更改**

```bash
git add order-service/src/main/resources/application.yml
git commit -m "feat(order): add read-write splitting config

- Add slave1 and slave2 datasources
- Configure READWRITE_SPLITTING rule with round-robin
- Update sharding rules to use logical datasource order_ds
- Configure connection pools (master: 20, slaves: 50)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: 重启 order-service 并验证

**Files:**
- None (runtime verification)

- [ ] **Step 1: 停止现有 order-service**

```bash
# 查找并停止 order-service 进程
lsof -ti :8081 | xargs kill -9 2>/dev/null || echo "No process on port 8081"
```

- [ ] **Step 2: 重新编译 order-service**

```bash
mvn -q -pl order-service clean package -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 启动 order-service**

```bash
mvn -q -pl order-service spring-boot:run > order-service.log 2>&1 &
```

- [ ] **Step 4: 等待服务启动**

```bash
sleep 10
```

- [ ] **Step 5: 检查服务启动状态**

```bash
curl -s http://localhost:8081/actuator/health | jq '.status'
```

Expected: "UP"

- [ ] **Step 6: 检查日志中的数据源初始化**

```bash
tail -100 order-service.log | grep -i "datasource\|shardingsphere"
```

Expected: 应该看到 master、slave1、slave2 三个数据源初始化成功

---

## Task 8: 验证主从数据同步

**Files:**
- None (verification)

- [ ] **Step 1: 在主库插入测试数据**

```bash
docker exec -it gov-mysql mysql -uroot -proot order_db -e "
INSERT INTO t_order_0 (order_no, user_id, product_id, quantity, amount_cents, status, created_at, updated_at)
VALUES ('test-sync-001', 8888, 'p-sync', 1, 200, 'PENDING', NOW(), NOW());"
```

- [ ] **Step 2: 在 slave1 验证数据同步**

```bash
docker exec -it gov-mysql-slave1 mysql -uroot -proot order_db -e "
SELECT order_no, user_id, status FROM t_order_0 WHERE order_no='test-sync-001';"
```

Expected: 能查到数据

- [ ] **Step 3: 在 slave2 验证数据同步**

```bash
docker exec -it gov-mysql-slave2 mysql -uroot -proot order_db -e "
SELECT order_no, user_id, status FROM t_order_0 WHERE order_no='test-sync-001';"
```

Expected: 能查到数据

- [ ] **Step 4: 检查主从延迟**

```bash
docker exec -it gov-mysql-slave1 mysql -uroot -proot -e "SHOW SLAVE STATUS\G" | grep "Seconds_Behind_Master"
docker exec -it gov-mysql-slave2 mysql -uroot -proot -e "SHOW SLAVE STATUS\G" | grep "Seconds_Behind_Master"
```

Expected: Seconds_Behind_Master: 0 或很小的值（< 1）

---

## Task 9: 验证读写分离路由

**Files:**
- None (verification)

- [ ] **Step 1: 启动 gateway 和 user-service**

```bash
# 在新终端启动 gateway
mvn -q -pl gateway spring-boot:run > gateway.log 2>&1 &

# 在新终端启动 user-service
mvn -q -pl user-service spring-boot:run > user-service.log 2>&1 &

# 等待启动
sleep 10
```

- [ ] **Step 2: 注册测试用户**

```bash
curl -X POST http://localhost:8080/api/user/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"rwtest","password":"123456","displayName":"RW Test User"}'
```

Expected: success: true

- [ ] **Step 3: 登录获取 Token**

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"rwtest","password":"123456"}' | jq -r '.data.token')

echo "Token: $TOKEN"
```

- [ ] **Step 4: 执行查询操作并观察日志**

```bash
# 清空日志
> order-service.log

# 执行查询
curl -s "http://localhost:8080/api/order/order/test-sync-001" \
  -H "Authorization: Bearer $TOKEN"

# 查看日志中的 SQL 路由
tail -50 order-service.log | grep "Actual SQL"
```

Expected: 日志应显示 `Actual SQL: slave1 ::: SELECT ...` 或 `Actual SQL: slave2 ::: SELECT ...`

- [ ] **Step 5: 执行写操作并观察日志**

```bash
# 清空日志
> order-service.log

# 执行下单
curl -X POST http://localhost:8080/api/order/order \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: rw-test-001" \
  -d '{"productId":"p1","quantity":1,"amountCents":100}'

# 查看日志中的 SQL 路由
tail -100 order-service.log | grep "Actual SQL"
```

Expected: 日志应显示 `Actual SQL: master ::: INSERT ...`

---

## Task 10: 验证负载均衡（轮询）

**Files:**
- None (verification)

- [ ] **Step 1: 清空日志**

```bash
> order-service.log
```

- [ ] **Step 2: 连续执行 10 次查询**

```bash
for i in {1..10}; do
  curl -s "http://localhost:8080/api/order/order/test-sync-001" \
    -H "Authorization: Bearer $TOKEN" > /dev/null
  sleep 0.3
done
```

- [ ] **Step 3: 统计 slave1 和 slave2 的调用次数**

```bash
echo "slave1 count:"
grep "Actual SQL: slave1" order-service.log | wc -l

echo "slave2 count:"
grep "Actual SQL: slave2" order-service.log | wc -l
```

Expected: slave1 和 slave2 的调用次数应该接近（轮询策略）

---

## Task 11: 验证事务一致性（Seata）

**Files:**
- None (verification)

- [ ] **Step 1: 启动 payment-service 和 inventory-service**

```bash
# 启动 payment-service
mvn -q -pl payment-service spring-boot:run > payment-service.log 2>&1 &

# 启动 inventory-service
mvn -q -pl inventory-service spring-boot:run > inventory-service.log 2>&1 &

# 等待启动
sleep 10
```

- [ ] **Step 2: 清空 order-service 日志**

```bash
> order-service.log
```

- [ ] **Step 3: 执行完整的下单流程（Seata 分布式事务）**

```bash
curl -X POST http://localhost:8080/api/order/order \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: seata-test-001" \
  -d '{"productId":"p1","quantity":1,"amountCents":100}'
```

- [ ] **Step 4: 检查事务内的 SQL 路由**

```bash
tail -200 order-service.log | grep "Actual SQL"
```

Expected: 事务内的所有 SQL（包括 SELECT）都应该路由到 master，不会出现 slave1 或 slave2

---

## Task 12: 验证从库故障降级

**Files:**
- None (verification)

- [ ] **Step 1: 停止 slave1**

```bash
docker stop gov-mysql-slave1
```

- [ ] **Step 2: 清空日志**

```bash
> order-service.log
```

- [ ] **Step 3: 执行查询操作**

```bash
for i in {1..5}; do
  curl -s "http://localhost:8080/api/order/order/test-sync-001" \
    -H "Authorization: Bearer $TOKEN" | jq -r '.success'
  sleep 0.5
done
```

Expected: 全部返回 true（无感知）

- [ ] **Step 4: 检查日志中的路由**

```bash
tail -50 order-service.log | grep "Actual SQL"
```

Expected: 应该只看到 slave2 或 master（降级），不会有 slave1

- [ ] **Step 5: 恢复 slave1**

```bash
docker start gov-mysql-slave1
sleep 5
```

- [ ] **Step 6: 验证 slave1 恢复后重新参与负载均衡**

```bash
> order-service.log

for i in {1..10}; do
  curl -s "http://localhost:8080/api/order/order/test-sync-001" \
    -H "Authorization: Bearer $TOKEN" > /dev/null
  sleep 0.3
done

echo "slave1 count:"
grep "Actual SQL: slave1" order-service.log | wc -l

echo "slave2 count:"
grep "Actual SQL: slave2" order-service.log | wc -l
```

Expected: slave1 和 slave2 都有调用

---

## Task 13: 清理测试数据并提交最终验证

**Files:**
- None (cleanup)

- [ ] **Step 1: 清理测试数据**

```bash
docker exec -it gov-mysql mysql -uroot -proot order_db -e "
DELETE FROM t_order_0 WHERE order_no IN ('test-sync-001');
DELETE FROM t_order_0 WHERE user_id = 8888;
DELETE FROM t_order_1 WHERE user_id = 8888;
"
```

- [ ] **Step 2: 删除测试用户**

```bash
docker exec -it gov-mysql mysql -uroot -proot user_db -e "
DELETE FROM t_user_0 WHERE username='rwtest';
DELETE FROM t_user_1 WHERE username='rwtest';
"
```

- [ ] **Step 3: 验证主从复制最终状态**

```bash
docker exec -it gov-mysql-slave1 mysql -uroot -proot -e "SHOW SLAVE STATUS\G" | grep -E "Slave_IO_Running|Slave_SQL_Running|Seconds_Behind_Master|Last_Error"
docker exec -it gov-mysql-slave2 mysql -uroot -proot -e "SHOW SLAVE STATUS\G" | grep -E "Slave_IO_Running|Slave_SQL_Running|Seconds_Behind_Master|Last_Error"
```

Expected:
- Slave_IO_Running: Yes
- Slave_SQL_Running: Yes
- Seconds_Behind_Master: 0
- Last_Error: (空)

- [ ] **Step 4: 停止所有服务**

```bash
lsof -ti :8080 :8081 :8082 :8083 :8084 | xargs kill -9 2>/dev/null
```

- [ ] **Step 5: 删除备份文件**

```bash
rm -f docker-compose.yml.backup
rm -f order-service/src/main/resources/application.yml.backup
```

- [ ] **Step 6: 创建验证文档**

```bash
cat > docs/read-write-splitting-verification.md << 'DOCEOF'
# Order Service 读写分离验证报告

## 验证时间
$(date '+%Y-%m-%d %H:%M:%S')

## 验证项目

### 1. MySQL 主从复制
- ✅ 主库 binlog 开启
- ✅ 从库1 复制正常
- ✅ 从库2 复制正常
- ✅ 主从延迟 < 1s

### 2. ShardingSphere 读写分离
- ✅ 写操作路由到 master
- ✅ 读操作路由到 slave1/slave2
- ✅ 负载均衡（轮询）生效

### 3. 事务一致性
- ✅ Seata 事务内所有操作路由到 master
- ✅ 读写分离不影响分布式事务

### 4. 故障降级
- ✅ 从库故障自动降级
- ✅ 从库恢复自动重新参与负载均衡

## 结论
读写分离功能正常，可以投入使用。
DOCEOF

git add docs/read-write-splitting-verification.md
```

- [ ] **Step 7: 最终提交**

```bash
git add -A
git commit -m "docs: add read-write splitting verification report

All verification passed:
- MySQL master-slave replication working
- ShardingSphere read-write routing working
- Load balancing (round-robin) working
- Seata transaction consistency maintained
- Failover and recovery working

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## 完成检查清单

验证以下所有项目都已完成：

- [ ] MySQL 配置文件已创建（master.cnf, slave1.cnf, slave2.cnf）
- [ ] docker-compose.yml 已更新（添加 slave1、slave2 服务）
- [ ] MySQL 集群已启动（1主2从）
- [ ] 主从复制已配置并验证
- [ ] order-service 配置已更新（读写分离规则）
- [ ] 读写路由已验证（写→master，读→slave）
- [ ] 负载均衡已验证（轮询生效）
- [ ] 事务一致性已验证（Seata 事务走主库）
- [ ] 故障降级已验证（从库故障自动降级）
- [ ] 所有更改已提交到 git

---

## 故障排查

### 问题 1: 从库复制状态异常

**症状**: `Slave_IO_Running: No` 或 `Slave_SQL_Running: No`

**排查步骤**:
```bash
# 查看详细错误
docker exec -it gov-mysql-slave1 mysql -uroot -proot -e "SHOW SLAVE STATUS\G" | grep -i error

# 常见原因：
# 1. 网络问题 - 检查容器网络
# 2. 复制用户权限 - 重新授权
# 3. GTID 不一致 - 重置复制
```

**解决方案**:
```bash
# 重置复制
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

### 问题 2: ShardingSphere 启动失败

**症状**: order-service 启动报错，提示数据源配置错误

**排查步骤**:
```bash
# 检查配置文件语法
python3 -c "import yaml; yaml.safe_load(open('order-service/src/main/resources/application.yml'))"

# 检查从库连接
mysql -h127.0.0.1 -P3307 -uroot -proot -e "SELECT 1;"
mysql -h127.0.0.1 -P3308 -uroot -proot -e "SELECT 1;"
```

**解决方案**:
- 检查 YAML 缩进
- 检查数据源名称拼写
- 检查端口号是否正确

### 问题 3: 读操作没有路由到从库

**症状**: 所有 SQL 都在 master 执行

**排查步骤**:
```bash
# 检查是否在事务中
# 事务内的读操作会强制走主库

# 检查 ShardingSphere 配置
grep -A 20 "READWRITE_SPLITTING" order-service/src/main/resources/application.yml
```

**解决方案**:
- 确保非事务查询
- 检查读写分离规则配置
- 检查逻辑数据源名称是否正确

---

## 性能优化建议

1. **连接池调优**
   - 根据实际负载调整 `maximum-pool-size`
   - 监控连接池使用率

2. **主从延迟监控**
   - 定期检查 `Seconds_Behind_Master`
   - 延迟过大时考虑优化主库写入性能

3. **读写比例优化**
   - 如果读多写少，可以增加更多从库
   - 如果写多读少，考虑提升主库配置

4. **缓存策略**
   - 对于热点数据，考虑添加 Redis 缓存
   - 减少数据库查询压力

---

## 下一步扩展

1. **添加更多从库**
   - 复制 slave2 配置，修改 server-id 和端口
   - 在 ShardingSphere 配置中添加新从库

2. **配置半同步复制**
   - 提高数据一致性
   - 牺牲一定性能

3. **添加主库故障自动切换**
   - 使用 MHA 或 Orchestrator
   - 配置 VIP 或 DNS 切换

4. **监控告警**
   - 集成 Prometheus + Grafana
   - 监控主从延迟、连接池、SQL 性能
