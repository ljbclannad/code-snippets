# Docker 部署指南

> 监控约定：`doctor-assistant-agent` 只暴露 `/actuator/prometheus` 指标，Prometheus 与 Grafana 统一由仓库根目录 `docker-compose.yml`、`docker/prometheus`、`docker/grafana` 管理。本目录不再维护独立监控编排。

## 概述

本项目提供完整的 Docker Compose 配置，支持一键启动所有依赖服务和应用。

## 架构

```
┌─────────────────────────────────────────────────────────┐
│                   Docker Network                         │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐             │
│  │  MySQL   │  │  Redis   │  │  Milvus  │ (可选)      │
│  │  :3306   │  │  :6379   │  │  :19530  │             │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘             │
│       │             │             │                     │
│       └─────────────┴─────────────┘                     │
│                     │                                   │
│            ┌────────▼────────┐                          │
│            │  Doctor Agent   │                          │
│            │     :8088       │                          │
│            └────────┬────────┘                          │
│                     │                                   │
│             指标暴露到根目录统一监控栈                  │
└─────────────────────────────────────────────────────────┘
```

## 快速开始

### 1. 基础启动（推荐）

启动 MySQL、Redis 和应用：

```bash
# 使用启动脚本
./start.sh start

# 或使用 docker-compose
docker-compose up -d mysql redis doctor-assistant-agent
```

**服务地址：**
- 应用: http://localhost:8088
- MySQL: localhost:3306 (root/root)
- Redis: localhost:6379

### 2. 完整启动（包含 Milvus）

启动所有服务，包括向量数据库：

```bash
# 使用启动脚本
./start.sh start-full

# 或使用 docker-compose
docker-compose --profile with-milvus up -d
```

**额外服务：**
- Milvus: localhost:19530
- Milvus Admin: http://localhost:9091
- MinIO: http://localhost:9001

### 3. 启动统一监控（仓库根目录）

在仓库根目录启动 Prometheus 和 Grafana：

```bash
cd ..
docker compose up -d prometheus grafana
```

**监控地址：**
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)

## 环境变量配置

### 必需配置

创建 `.env` 文件（可选）：

```bash
# DeepSeek API Key（未设置时使用 Mock 模式）
DEEPSEEK_API_KEY=your-deepseek-api-key

# Agent 配置
AGENT_USE_MOCK=false
AGENT_MAX_STEPS=15

# Milvus 配置
MILVUS_ENABLED=false
```

### 配置说明

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥 | - |
| `AGENT_USE_MOCK` | 是否使用 Mock 模式 | false |
| `AGENT_MAX_STEPS` | Agent 最大执行步数 | 15 |
| `MILVUS_ENABLED` | 是否启用 Milvus | false |
| `MYSQL_PASSWORD` | MySQL root 密码 | root |

## 常用命令

### 启动脚本命令

```bash
# 启动基础服务
./start.sh start

# 启动完整服务（包括 Milvus）
./start.sh start-full

# 停止所有服务
./start.sh stop

# 重启应用
./start.sh restart

# 查看应用日志
./start.sh logs

# 查看服务状态
./start.sh status
```

### Docker Compose 命令

```bash
# 启动所有服务
docker-compose up -d

# 停止所有服务
docker-compose down

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f doctor-assistant-agent

# 重启应用
docker-compose restart doctor-assistant-agent

# 进入容器
docker-compose exec doctor-assistant-agent sh

# 清理所有数据（危险操作）
docker-compose down -v
```

## 服务配置

### MySQL

**默认配置：**
- 端口: 3306
- 用户: root
- 密码: root
- 数据库: doctor_assistant

**数据持久化：**
- 数据卷: `mysql-data`
- 初始化脚本: `sql/schema.sql`, `sql/test_data.sql`

**连接字符串：**
```
jdbc:mysql://localhost:3306/doctor_assistant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
```

### Redis

**默认配置：**
- 端口: 6379
- 持久化: AOF

**数据持久化：**
- 数据卷: `redis-data`

**连接测试：**
```bash
redis-cli -h localhost -p 6379 ping
```

### Milvus

**默认配置：**
- 端口: 19530 (gRPC)
- 管理端口: 9091 (HTTP)

**依赖服务：**
- etcd: 元数据存储
- MinIO: 对象存储

**数据持久化：**
- 数据卷: `milvus-data`, `milvus-etcd-data`, `milvus-minio-data`

### 应用

**默认配置：**
- 端口: 8088
- JVM: -Xms512m -Xmx1024m

**健康检查：**
```bash
curl http://localhost:8088/actuator/health
```

**Prometheus 指标：**
```bash
curl http://localhost:8088/actuator/prometheus
```

## 监控配置

### Prometheus 与 Grafana

**统一配置位置：**
- 根目录 Prometheus: `docker/prometheus/prometheus.yml`
- 根目录告警规则: `docker/prometheus/alerts`
- 根目录 Grafana Dashboard: `docker/grafana/provisioning/dashboards`

**抓取配置：**
- 应用指标: `host.docker.internal:8088/actuator/prometheus`
- 抓取作业: `doctor-assistant-agent`

**访问地址：**
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000

### 关键指标

**Agent 指标：**
- `agent_execution_total` - 总执行次数
- `agent_execution_success` - 成功次数
- `agent_execution_failure` - 失败次数
- `agent_execution_duration` - 执行耗时
- `agent_execution_steps` - 执行步数

**工具指标：**
- `tool_calls_total` - 工具调用总数
- `tool_calls_success` - 成功次数
- `tool_calls_failure` - 失败次数
- `tool_execution_duration` - 执行耗时

**向量检索指标：**
- `vector_search_requests` - 检索请求数
- `vector_search_duration` - 检索耗时
- `vector_cache_hits` - 缓存命中数

## 数据持久化

### 数据卷列表

| 数据卷 | 用途 | 大小估算 |
|--------|------|----------|
| `mysql-data` | MySQL 数据 | ~1GB |
| `redis-data` | Redis 数据 | ~100MB |
| `milvus-data` | Milvus 向量数据 | ~500MB |
| `milvus-etcd-data` | Milvus 元数据 | ~50MB |
| `milvus-minio-data` | Milvus 对象存储 | ~500MB |
| `app-logs` | 应用日志 | ~100MB |

### 备份数据

```bash
# 备份 MySQL
docker-compose exec mysql mysqldump -uroot -proot doctor_assistant > backup.sql

# 备份 Redis
docker-compose exec redis redis-cli SAVE
docker cp doctor-assistant-redis:/data/dump.rdb ./redis-backup.rdb

# 备份所有数据卷
docker run --rm -v doctor-assistant-agent_mysql-data:/data -v $(pwd):/backup alpine tar czf /backup/mysql-backup.tar.gz /data
```

### 恢复数据

```bash
# 恢复 MySQL
docker-compose exec -T mysql mysql -uroot -proot doctor_assistant < backup.sql

# 恢复 Redis
docker cp ./redis-backup.rdb doctor-assistant-redis:/data/dump.rdb
docker-compose restart redis
```

## 故障排查

### 1. 应用启动失败

**检查日志：**
```bash
docker-compose logs doctor-assistant-agent
```

**常见问题：**
- MySQL 未就绪：等待 MySQL 健康检查通过
- Redis 连接失败：检查 Redis 是否启动
- 端口冲突：修改 docker-compose.yml 中的端口映射

### 2. MySQL 连接失败

**检查 MySQL 状态：**
```bash
docker-compose ps mysql
docker-compose logs mysql
```

**手动连接测试：**
```bash
docker-compose exec mysql mysql -uroot -proot -e "SELECT 1"
```

### 3. Redis 连接失败

**检查 Redis 状态：**
```bash
docker-compose ps redis
docker-compose exec redis redis-cli ping
```

### 4. Milvus 启动失败

**检查依赖服务：**
```bash
docker-compose ps milvus-etcd milvus-minio
```

**查看 Milvus 日志：**
```bash
docker-compose logs milvus-standalone
```

### 5. 内存不足

**调整 JVM 参数：**

编辑 `docker-compose.yml`：
```yaml
environment:
  JAVA_OPTS: -Xms256m -Xmx512m -XX:+UseG1GC
```

### 6. 磁盘空间不足

**清理未使用的镜像和容器：**
```bash
docker system prune -a
```

**清理数据卷（危险）：**
```bash
docker-compose down -v
```

## 性能优化

### 1. 数据库优化

**MySQL 配置：**
```yaml
command: 
  - --max_connections=200
  - --innodb_buffer_pool_size=512M
```

### 2. Redis 优化

**启用持久化：**
```yaml
command: redis-server --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru
```

### 3. 应用优化

**增加 JVM 内存：**
```yaml
environment:
  JAVA_OPTS: -Xms1g -Xmx2g -XX:+UseG1GC
```

### 4. Milvus 优化

**调整索引参数：**
- 小数据集: IVF_FLAT, nlist=128
- 大数据集: HNSW, M=16

## 生产环境部署

### 1. 安全配置

**修改默认密码：**
```yaml
environment:
  MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
  REDIS_PASSWORD: ${REDIS_PASSWORD}
```

**启用 TLS：**
- MySQL: 配置 SSL 证书
- Redis: 启用 TLS 模式

### 2. 资源限制

**添加资源限制：**
```yaml
deploy:
  resources:
    limits:
      cpus: '2'
      memory: 2G
    reservations:
      cpus: '1'
      memory: 1G
```

### 3. 日志管理

**配置日志驱动：**
```yaml
logging:
  driver: "json-file"
  options:
    max-size: "10m"
    max-file: "3"
```

### 4. 健康检查

所有服务已配置健康检查，确保服务正常运行。

## 参考资料

- [Docker Compose 文档](https://docs.docker.com/compose/)
- [MySQL Docker 镜像](https://hub.docker.com/_/mysql)
- [Redis Docker 镜像](https://hub.docker.com/_/redis)
- [Milvus 文档](https://milvus.io/docs)
- [Prometheus 文档](https://prometheus.io/docs/)
- [Grafana 文档](https://grafana.com/docs/)
