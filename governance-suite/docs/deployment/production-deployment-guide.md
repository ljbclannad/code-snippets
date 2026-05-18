# AI对话流程重构 - 生产部署指南

## 1. 部署前准备

### 1.1 环境要求

- Java 17+
- Redis 7.0+
- MySQL 8.0+
- RabbitMQ 3.11+
- Elasticsearch 8.0+

### 1.2 配置检查清单

- [ ] Redis连接配置正确
- [ ] MySQL数据库已创建
- [ ] RabbitMQ队列已创建
- [ ] Elasticsearch索引已创建
- [ ] DeepSeek API密钥已配置
- [ ] Nacos服务注册中心已启动

## 2. 部署步骤

### 2.1 停机维护（预计2-3小时）

**时间窗口**: 建议选择凌晨2:00-5:00低峰期

#### Step 1: 停止服务

```bash
# 停止ai-chat-service
systemctl stop ai-chat-service

# 停止user-profile-service
systemctl stop user-profile-service
```

#### Step 2: 备份数据

```bash
# 备份MySQL
mysqldump -u root -p governance_db > backup_$(date +%Y%m%d_%H%M%S).sql

# 备份Redis（可选）
redis-cli --rdb /backup/redis_$(date +%Y%m%d_%H%M%S).rdb
```

#### Step 3: 部署新版本

```bash
# 部署user-profile-service
cd user-profile-service
mvn clean package -DskipTests
cp target/user-profile-service-1.0.0.jar /opt/services/
systemctl start user-profile-service

# 等待启动完成
sleep 30

# 部署ai-chat-service
cd ../ai-chat-service
mvn clean package -DskipTests
cp target/ai-chat-service-1.0.0.jar /opt/services/
systemctl start ai-chat-service
```

#### Step 4: 验证部署

```bash
# 检查服务健康
curl http://localhost:8104/actuator/health  # user-profile-service
curl http://localhost:8103/actuator/health  # ai-chat-service

# 测试对话功能
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "prod-test-001",
    "customerId": "test-user-001",
    "groupId": "test-group-001",
    "message": "测试消息"
  }'
```

## 3. 监控和告警

### 3.1 Prometheus配置

在Prometheus配置文件中添加：

```yaml
scrape_configs:
  - job_name: 'ai-chat-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8103']
  
  - job_name: 'user-profile-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8104']
```

### 3.2 关键指标

- `ai_chat_requests_total` - 对话请求总数
- `ai_chat_response_time_seconds` - 响应时间
- `ai_profile_cache_hit_total` / `ai_profile_cache_miss_total` - 画像缓存命中率
- `ai_context_cache_hit_total` / `ai_context_cache_miss_total` - 上下文缓存命中率
- `ai_budget_compression_total` - 预算压缩次数

### 3.3 告警规则

```yaml
groups:
  - name: ai-chat-alerts
    rules:
      - alert: ChatResponseTimeSlow
        expr: histogram_quantile(0.95, ai_chat_response_time_seconds_bucket) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "对话响应时间P95超过5秒"
      
      - alert: ProfileCacheHitRateLow
        expr: |
          ai_profile_cache_hit_total / 
          (ai_profile_cache_hit_total + ai_profile_cache_miss_total) < 0.8
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "画像缓存命中率低于80%"
```

## 4. 回滚方案

如果部署出现问题，执行以下回滚步骤：

```bash
# 1. 停止新版本服务
systemctl stop ai-chat-service
systemctl stop user-profile-service

# 2. 恢复旧版本
cp /backup/ai-chat-service-old.jar /opt/services/ai-chat-service-1.0.0.jar
cp /backup/user-profile-service-old.jar /opt/services/user-profile-service-1.0.0.jar

# 3. 恢复数据库（如果需要）
mysql -u root -p governance_db < backup_YYYYMMDD_HHMMSS.sql

# 4. 启动旧版本服务
systemctl start user-profile-service
systemctl start ai-chat-service

# 5. 验证服务正常
curl http://localhost:8103/actuator/health
```

## 5. 性能基准

部署后验证以下性能指标：

| 指标 | 目标值 | 验证方法 |
|------|--------|---------|
| 画像获取时间（缓存命中） | < 50ms | 查看Prometheus指标 |
| 记忆检索时间 | < 400ms | 查看Prometheus指标 |
| 对话响应P95 | < 5s | 查看Prometheus指标 |
| 画像缓存命中率 | > 80% | 查看Prometheus指标 |
| 上下文缓存命中率 | > 70% | 查看Prometheus指标 |

## 6. 常见问题

**Q: Redis连接失败怎么办？**  
A: 服务会自动降级到MySQL，不影响主流程，但性能会下降。

**Q: Elasticsearch查询超时怎么办？**  
A: 检查Elasticsearch服务状态，必要时重启Elasticsearch服务。

**Q: 监控指标不显示怎么办？**  
A: 检查Prometheus配置是否正确，确认服务的/actuator/prometheus端点可访问。
