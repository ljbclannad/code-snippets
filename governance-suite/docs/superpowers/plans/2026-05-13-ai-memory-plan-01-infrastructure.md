# AI 记忆功能实施计划 - Plan 1: 基础设施和数据库

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 AI 记忆功能所需的基础设施，包括 Milvus 向量数据库、数据库表结构和项目配置

**Architecture:** 扩展现有的 docker-compose.yml 添加 Milvus 及其依赖（etcd、MinIO），创建 MySQL 数据库表，更新 Maven 配置支持 Spring AI

**Tech Stack:** Docker Compose、MySQL、Milvus 2.3.4、etcd、MinIO、Maven

---

## 文件结构

本计划将创建或修改以下文件：

- **Modify**: `pom.xml` - 添加 Spring AI 依赖管理和新模块
- **Modify**: `docker-compose.yml` - 添加 Milvus、etcd、MinIO 服务
- **Create**: `sql/05-ai-memory-schema.sql` - 数据库初始化脚本
- **Create**: `.env.example` - 环境变量模板
- **Create**: `start-ai-services.sh` - 服务启动脚本
- **Create**: `stop-ai-services.sh` - 服务停止脚本

---

## Task 1: 更新父 pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 备份当前 pom.xml**

```bash
cp pom.xml pom.xml.backup
```

- [ ] **Step 2: 在 properties 中添加 Spring AI 版本**

在 `<properties>` 标签中添加：

```xml
<!-- 新增：Spring AI -->
<spring-ai.version>1.0.0-M3</spring-ai.version>
```

- [ ] **Step 3: 在 dependencyManagement 中添加 Spring AI BOM**

在 `<dependencyManagement><dependencies>` 中添加：

```xml
<!-- 新增：Spring AI BOM -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-bom</artifactId>
    <version>${spring-ai.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 4: 添加 Spring Milestones 仓库**

在 `<repositories>` 标签中添加（如果没有 repositories 标签则创建）：

```xml
<repositories>
    <repository>
        <id>spring-milestones</id>
        <name>Spring Milestones</name>
        <url>https://repo.spring.io/milestone</url>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
    </repository>
</repositories>
```

- [ ] **Step 5: 在 modules 中添加新模块**

在 `<modules>` 标签中添加：

```xml
<!-- 新增模块 -->
<module>user-profile-service</module>
<module>vector-memory-service</module>
<module>ai-chat-service</module>
```

- [ ] **Step 6: 验证 pom.xml 格式**

```bash
mvn validate
```

Expected: BUILD SUCCESS

- [ ] **Step 7: 提交更改**

```bash
git add pom.xml
git commit -m "build: add Spring AI dependencies and new modules"
```

---

## Task 2: 扩展 docker-compose.yml

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: 备份当前 docker-compose.yml**

```bash
cp docker-compose.yml docker-compose.yml.backup
```

- [ ] **Step 2: 在 services 中添加 etcd 服务**

在 `services:` 下添加：

```yaml
  # Milvus 依赖的 etcd
  etcd:
    image: quay.io/coreos/etcd:v3.5.5
    container_name: gov-etcd
    environment:
      - ETCD_AUTO_COMPACTION_MODE=revision
      - ETCD_AUTO_COMPACTION_RETENTION=1000
      - ETCD_QUOTA_BACKEND_BYTES=4294967296
      - ETCD_SNAPSHOT_COUNT=50000
    volumes:
      - gov_etcd_data:/etcd
    command: etcd -advertise-client-urls=http://127.0.0.1:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd
    healthcheck:
      test: ["CMD", "etcdctl", "endpoint", "health"]
      interval: 30s
      timeout: 10s
      retries: 3
```

- [ ] **Step 3: 在 services 中添加 MinIO 服务**

```yaml
  # Milvus 依赖的 MinIO
  minio:
    image: minio/minio:RELEASE.2023-03-20T20-16-18Z
    container_name: gov-minio
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - gov_minio_data:/minio_data
    command: minio server /minio_data --console-address ":9001"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 30s
      timeout: 10s
      retries: 3
```

- [ ] **Step 4: 在 services 中添加 Milvus 服务**

```yaml
  # Milvus 向量数据库
  milvus:
    image: milvusdb/milvus:v2.3.4
    container_name: gov-milvus
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: minio:9000
      MINIO_ACCESS_KEY_ID: minioadmin
      MINIO_SECRET_ACCESS_KEY: minioadmin
    ports:
      - "19530:19530"
      - "9091:9091"
    volumes:
      - gov_milvus_data:/var/lib/milvus
    depends_on:
      etcd:
        condition: service_healthy
      minio:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9091/healthz"]
      interval: 30s
      timeout: 10s
      retries: 3
```

- [ ] **Step 5: 在 volumes 中添加新的 volume**

在 `volumes:` 下添加：

```yaml
  gov_etcd_data:
  gov_minio_data:
  gov_milvus_data:
```

- [ ] **Step 6: 验证 docker-compose.yml 格式**

```bash
docker compose config
```

Expected: 输出完整的配置，无错误

- [ ] **Step 7: 提交更改**

```bash
git add docker-compose.yml
git commit -m "build: add Milvus, etcd, and MinIO to docker-compose"
```

---

## Task 3: 创建数据库初始化脚本

**Files:**
- Create: `sql/05-ai-memory-schema.sql`

- [ ] **Step 1: 创建 SQL 文件**

```bash
touch sql/05-ai-memory-schema.sql
```

- [ ] **Step 2: 写入数据库初始化脚本**

```sql
-- =============================================
-- AI 记忆功能数据库初始化脚本
-- =============================================

USE governance;

-- 1. 创建群组记忆表
DROP TABLE IF EXISTS `b_ai_group_memory`;
CREATE TABLE `b_ai_group_memory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `group_id` VARCHAR(64) NOT NULL COMMENT '群组ID',
  `memory_type` VARCHAR(32) NOT NULL COMMENT '记忆类型: SYMPTOM/ALLERGY/MEDICATION/DIET/PREFERENCE/OTHER',
  `memory_key` VARCHAR(128) NOT NULL COMMENT '记忆关键词',
  `memory_content` TEXT NOT NULL COMMENT '记忆内容',
  `embedding_id` VARCHAR(128) COMMENT '向量数据库中的ID',
  `confidence_score` DECIMAL(3,2) COMMENT '置信度分数 0-1',
  `source_message` TEXT COMMENT '来源消息',
  `extracted_at` BIGINT COMMENT '提取时间戳',
  `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_group` (`customer_id`, `group_id`, `is_deleted`),
  KEY `idx_memory_type` (`memory_type`),
  KEY `idx_embedding_id` (`embedding_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI群组记忆表';

-- 2. 创建用户全局记忆表
DROP TABLE IF EXISTS `b_ai_user_memory`;
CREATE TABLE `b_ai_user_memory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `memory_type` VARCHAR(32) NOT NULL COMMENT '记忆类型: ALLERGY/CHRONIC_DISEASE/MEDICATION/DIET_RESTRICTION/OTHER',
  `memory_key` VARCHAR(128) NOT NULL COMMENT '记忆关键词',
  `memory_content` TEXT NOT NULL COMMENT '记忆内容',
  `embedding_id` VARCHAR(128) COMMENT '向量数据库中的ID',
  `confidence_score` DECIMAL(3,2) COMMENT '置信度分数 0-1',
  `source` VARCHAR(64) COMMENT '来源: CHAT/MANUAL/IMPORT',
  `verified` TINYINT DEFAULT 0 COMMENT '是否已验证: 0=未验证, 1=已验证',
  `verified_by` VARCHAR(64) COMMENT '验证人ID',
  `verified_at` DATETIME COMMENT '验证时间',
  `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_type_key` (`customer_id`, `memory_type`, `memory_key`, `is_deleted`),
  KEY `idx_embedding_id` (`embedding_id`),
  KEY `idx_verified` (`verified`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI用户全局记忆表';

-- 3. 创建聊天历史表
DROP TABLE IF EXISTS `b_ai_chat_history`;
CREATE TABLE `b_ai_chat_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `group_id` VARCHAR(64) NOT NULL COMMENT '群组ID',
  `conversation_id` VARCHAR(128) NOT NULL COMMENT 'Dify会话ID',
  `user_message` TEXT NOT NULL COMMENT '用户消息',
  `ai_response` TEXT COMMENT 'AI回复',
  `role` VARCHAR(32) COMMENT '角色类型: doctor/nutritionist/customer',
  `trace_id` VARCHAR(64) COMMENT '追踪ID',
  `response_time` INT COMMENT '响应时间(毫秒)',
  `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_group` (`customer_id`, `group_id`, `is_deleted`),
  KEY `idx_conversation` (`conversation_id`),
  KEY `idx_trace_id` (`trace_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI聊天历史表';

-- 4. 插入测试数据（可选）
INSERT INTO `b_ai_user_memory` 
  (`customer_id`, `memory_type`, `memory_key`, `memory_content`, `confidence_score`, `source`, `verified`) 
VALUES 
  ('user123', 'ALLERGY', '青霉素', '对青霉素过敏', 1.0, 'MANUAL', 1),
  ('user123', 'CHRONIC_DISEASE', '高血压', '高血压患者，正在服用降压药', 1.0, 'MANUAL', 1);

COMMIT;
```

- [ ] **Step 3: 提交更改**

```bash
git add sql/05-ai-memory-schema.sql
git commit -m "feat: add AI memory database schema"
```

---

## Task 4: 创建环境变量模板

**Files:**
- Create: `.env.example`

- [ ] **Step 1: 创建 .env.example 文件**

```bash
cat > .env.example << 'EOF'
# =============================================
# AI 记忆功能环境变量配置
# =============================================

# 阿里云通义千问 API Key
# 获取地址: https://dashscope.console.aliyun.com/
DASHSCOPE_API_KEY=your-dashscope-api-key-here

# DeepSeek API Key
# 获取地址: https://platform.deepseek.com/
DEEPSEEK_API_KEY=your-deepseek-api-key-here

# Dify API Key
# 获取地址: https://cloud.dify.ai/
DIFY_API_KEY=your-dify-api-key-here
EOF
```

- [ ] **Step 2: 确保 .env 在 .gitignore 中**

```bash
grep -q "^\.env$" .gitignore || echo ".env" >> .gitignore
```

- [ ] **Step 3: 提交更改**

```bash
git add .env.example .gitignore
git commit -m "build: add environment variable template"
```

---

## Task 5: 创建服务启动脚本

**Files:**
- Create: `start-ai-services.sh`
- Create: `stop-ai-services.sh`

- [ ] **Step 1: 创建启动脚本**

```bash
cat > start-ai-services.sh << 'EOF'
#!/bin/bash

echo "=== 启动 AI 记忆服务 ==="

# 检查环境变量
if [ -z "$DASHSCOPE_API_KEY" ]; then
    echo "错误: DASHSCOPE_API_KEY 未设置"
    echo "请先执行: export DASHSCOPE_API_KEY=your-key"
    exit 1
fi

if [ -z "$DEEPSEEK_API_KEY" ]; then
    echo "错误: DEEPSEEK_API_KEY 未设置"
    echo "请先执行: export DEEPSEEK_API_KEY=your-key"
    exit 1
fi

if [ -z "$DIFY_API_KEY" ]; then
    echo "错误: DIFY_API_KEY 未设置"
    echo "请先执行: export DIFY_API_KEY=your-key"
    exit 1
fi

# 检查基础设施
echo "检查基础设施状态..."
docker compose ps | grep -q "gov-mysql.*Up" || { echo "MySQL 未启动"; exit 1; }
docker compose ps | grep -q "gov-redis.*Up" || { echo "Redis 未启动"; exit 1; }
docker compose ps | grep -q "gov-rabbitmq.*Up" || { echo "RabbitMQ 未启动"; exit 1; }
docker compose ps | grep -q "gov-nacos.*Up" || { echo "Nacos 未启动"; exit 1; }
docker compose ps | grep -q "gov-milvus.*Up" || { echo "Milvus 未启动"; exit 1; }

echo "基础设施检查通过"

# 创建日志目录
mkdir -p logs

# 启动服务
echo "启动 user-profile-service (8101)..."
mvn -q -pl user-profile-service spring-boot:run > logs/user-profile-service.log 2>&1 &
echo $! > logs/user-profile-service.pid

sleep 10

echo "启动 vector-memory-service (8102)..."
mvn -q -pl vector-memory-service spring-boot:run > logs/vector-memory-service.log 2>&1 &
echo $! > logs/vector-memory-service.pid

sleep 10

echo "启动 ai-chat-service (8103)..."
mvn -q -pl ai-chat-service spring-boot:run > logs/ai-chat-service.log 2>&1 &
echo $! > logs/ai-chat-service.pid

echo ""
echo "=== 启动完成 ==="
echo "user-profile-service: http://localhost:8101"
echo "vector-memory-service: http://localhost:8102"
echo "ai-chat-service: http://localhost:8103"
echo ""
echo "查看日志: tail -f logs/*.log"
echo "停止服务: ./stop-ai-services.sh"
EOF
```

- [ ] **Step 2: 创建停止脚本**

```bash
cat > stop-ai-services.sh << 'EOF'
#!/bin/bash

echo "=== 停止 AI 记忆服务 ==="

for service in user-profile-service vector-memory-service ai-chat-service; do
    if [ -f logs/${service}.pid ]; then
        pid=$(cat logs/${service}.pid)
        echo "停止 ${service} (PID: ${pid})..."
        kill $pid 2>/dev/null || echo "${service} 已停止"
        rm logs/${service}.pid
    fi
done

echo "=== 停止完成 ==="
EOF
```

- [ ] **Step 3: 添加执行权限**

```bash
chmod +x start-ai-services.sh stop-ai-services.sh
```

- [ ] **Step 4: 提交更改**

```bash
git add start-ai-services.sh stop-ai-services.sh
git commit -m "build: add service start/stop scripts"
```

---

## Task 6: 启动基础设施并验证

**Files:**
- None (verification only)

- [ ] **Step 1: 启动 Docker Compose**

```bash
docker compose up -d
```

Expected: 所有服务启动成功

- [ ] **Step 2: 等待服务就绪**

```bash
sleep 60
```

- [ ] **Step 3: 验证 MySQL**

```bash
mysql -h 127.0.0.1 -P 3306 -u root -proot -e "SHOW DATABASES;" | grep governance
```

Expected: 输出包含 "governance"

- [ ] **Step 4: 验证 Redis**

```bash
redis-cli -p 36379 ping
```

Expected: PONG

- [ ] **Step 5: 验证 RabbitMQ**

```bash
curl -s http://localhost:15672/api/overview -u guest:guest | grep -q "rabbitmq_version"
```

Expected: 命令成功执行（退出码 0）

- [ ] **Step 6: 验证 Nacos**

```bash
curl -s http://localhost:8848/nacos/v1/console/health/liveness | grep -q "UP"
```

Expected: 输出包含 "UP"

- [ ] **Step 7: 验证 Milvus**

```bash
curl -s http://localhost:9091/healthz
```

Expected: 输出 "OK" 或类似健康状态

- [ ] **Step 8: 验证 etcd**

```bash
docker exec gov-etcd etcdctl endpoint health
```

Expected: 输出包含 "is healthy"

- [ ] **Step 9: 验证 MinIO**

```bash
curl -s http://localhost:9000/minio/health/live
```

Expected: HTTP 200 响应

---

## Task 7: 执行数据库初始化

**Files:**
- None (database operation)

- [ ] **Step 1: 执行 SQL 脚本**

```bash
mysql -h 127.0.0.1 -P 3306 -u root -proot governance < sql/05-ai-memory-schema.sql
```

Expected: 命令成功执行，无错误

- [ ] **Step 2: 验证表创建**

```bash
mysql -h 127.0.0.1 -P 3306 -u root -proot governance -e "SHOW TABLES LIKE 'b_ai%';"
```

Expected: 输出包含三张表：
- b_ai_group_memory
- b_ai_user_memory
- b_ai_chat_history

- [ ] **Step 3: 验证测试数据**

```bash
mysql -h 127.0.0.1 -P 3306 -u root -proot governance -e "SELECT COUNT(*) FROM b_ai_user_memory;"
```

Expected: 输出 "2"（两条测试数据）

- [ ] **Step 4: 验证表结构**

```bash
mysql -h 127.0.0.1 -P 3306 -u root -proot governance -e "DESC b_ai_group_memory;"
```

Expected: 输出表结构，包含所有字段

---

## Task 8: 创建 logs 目录并配置 .gitignore

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: 创建 logs 目录**

```bash
mkdir -p logs
```

- [ ] **Step 2: 添加 logs 到 .gitignore**

```bash
grep -q "^logs/$" .gitignore || echo "logs/" >> .gitignore
```

- [ ] **Step 3: 添加 PID 文件到 .gitignore**

```bash
grep -q "^\*.pid$" .gitignore || echo "*.pid" >> .gitignore
```

- [ ] **Step 4: 提交更改**

```bash
git add .gitignore
git commit -m "build: ignore logs directory and PID files"
```

---

## 验证清单

完成所有任务后，验证以下内容：

- [ ] `pom.xml` 包含 Spring AI 依赖和新模块
- [ ] `docker-compose.yml` 包含 Milvus、etcd、MinIO 服务
- [ ] 所有 Docker 容器正常运行
- [ ] MySQL 中存在三张 AI 记忆表
- [ ] 测试数据已插入
- [ ] 启动/停止脚本可执行
- [ ] `.env.example` 文件存在
- [ ] 所有更改已提交到 Git

---

## 下一步

基础设施搭建完成后，继续执行：

**Plan 2: user-profile-service** - 实现用户画像服务
