# Prometheus + Grafana 监控使用指南

## 一、快速启动

### 1. 启动监控服务

```bash
# 在项目根目录
cd /Users/lejinbo/LLM/code-snippets/governance-suite

# 启动 Prometheus + Grafana（以及其他基础设施）
docker compose up -d prometheus grafana

# 或者启动所有服务
docker compose up -d
```

### 2. 验证服务状态

```bash
# 检查容器运行状态
docker ps | grep -E "prometheus|grafana"

# 应该看到：
# gov-prometheus   运行中   0.0.0.0:9090->9090/tcp
# gov-grafana      运行中   0.0.0.0:3000->3000/tcp
```

### 3. 访问监控界面

| 服务 | 地址 | 用户名 | 密码 |
|------|------|--------|------|
| **Prometheus** | http://localhost:9090 | - | - |
| **Grafana** | http://localhost:3000 | admin | admin |

---

## 二、配置说明

### 1. Prometheus 配置

**配置文件位置**：`docker/prometheus/prometheus.yml`

**已配置的监控目标**：
- `ai-chat-service` (8100)
- `gateway` (8080)
- `user-service` (8084)
- `order-service` (8081)
- `payment-service` (8082)
- `inventory-service` (8083)
- `outbox-consumer` (8090)
- `doctor-assistant-agent` (8088)

**抓取间隔**：15 秒

### 2. Grafana 配置

**配置文件位置**：
- 数据源：`docker/grafana/provisioning/datasources/prometheus.yml`
- 仪表盘：`docker/grafana/provisioning/dashboards/`

**预置仪表盘**：
- `ai-chat-service.json` - AI 聊天服务监控
- `doctor-assistant-agent.json` - 医生助手 Agent 监控

**说明**：
- `doctor-assistant-agent` 的 JVM、CPU、基础 Agent 指标会在服务启动后出现
- 工具调用、向量检索这类按标签动态注册的指标，需要实际发生请求后才会出现在 Prometheus 和 Grafana 中

---

## 三、使用 Grafana

### 1. 首次登录

1. 访问 http://localhost:3000
2. 使用默认账号登录：`admin / admin`
3. 首次登录会提示修改密码（可跳过）

### 2. 查看预置仪表盘

1. 点击左侧菜单 **Dashboards** → **Browse**
2. 选择 **AI Chat Service 监控**

### 3. 仪表盘面板说明

| 面板名称 | 指标 | 说明 |
|---------|------|------|
| **AI Chat QPS** | `rate(ai_chat_requests_total[1m])` | 每秒请求数 |
| **总请求数** | `ai_chat_requests_total` | 累计请求总数 |
| **响应时间分布** | P50/P95/P99 | 响应时间百分位数 |
| **缓存命中率** | 上下文缓存 / 画像缓存 | 缓存效率 |
| **异常指标监控** | 记忆去重 / Token 压缩 | 异常事件频率 |
| **各组件平均耗时** | 画像/记忆/向量化/分析 | 各环节性能 |

### 4. 创建自定义仪表盘

1. 点击 **+** → **Dashboard** → **Add new panel**
2. 在 **Metrics browser** 中输入 PromQL 查询
3. 常用查询示例：

```promql
# QPS
rate(ai_chat_requests_total[1m])

# P95 响应时间
histogram_quantile(0.95, rate(ai_chat_response_time_seconds_bucket[5m]))

# 缓存命中率
ai_context_cache_hit_total / (ai_context_cache_hit_total + ai_context_cache_miss_total) * 100

# 记忆去重频率
rate(ai_memory_duplicate_total[5m])
```

---

## 四、使用 Prometheus

### 1. 查询指标

访问 http://localhost:9090，在查询框中输入指标名称：

```promql
# 查看所有 AI Chat 相关指标
{application="ai-chat-service"}

# 查看请求总数
ai_chat_requests_total

# 查看缓存命中次数
ai_context_cache_hit_total
```

### 2. 查看监控目标状态

1. 访问 http://localhost:9090/targets
2. 查看各服务的抓取状态（UP/DOWN）

**注意**：如果服务显示 DOWN，确保：
- 服务已启动（`mvn spring-boot:run`）
- Actuator 端点已暴露（`/actuator/prometheus`）

### 3. 验证指标是否正常

```bash
# 直接访问服务的 Prometheus 端点
curl http://localhost:8100/actuator/prometheus | grep "ai_chat"

# 应该看到类似输出：
# ai_chat_requests_total{application="ai-chat-service"} 0.0
# ai_context_cache_hit_total{application="ai-chat-service"} 0.0
```

---

## 五、告警配置（可选）

### 1. 创建告警规则

创建文件 `docker/prometheus/alerts.yml`：

```yaml
groups:
  - name: ai_chat_alerts
    interval: 30s
    rules:
      # 响应时间过长
      - alert: HighResponseTime
        expr: histogram_quantile(0.95, rate(ai_chat_response_time_seconds_bucket[5m])) > 5
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "AI Chat 响应时间过长"
          description: "P95 响应时间超过 5 秒，当前值：{{ $value }}s"

      # 缓存命中率过低
      - alert: LowCacheHitRate
        expr: |
          (ai_context_cache_hit_total / 
           (ai_context_cache_hit_total + ai_context_cache_miss_total)) < 0.5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "上下文缓存命中率过低"
          description: "缓存命中率低于 50%，当前值：{{ $value | humanizePercentage }}"

      # Token 预算频繁压缩
      - alert: FrequentBudgetCompression
        expr: rate(ai_budget_compression_total[5m]) > 0.2
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Token 预算频繁压缩"
          description: "可能配置过小或对话复杂度过高"
```

### 2. 修改 Prometheus 配置

编辑 `docker/prometheus/prometheus.yml`，添加：

```yaml
rule_files:
  - "alerts.yml"
```

### 3. 重启 Prometheus

```bash
docker compose restart prometheus
```

### 4. 查看告警状态

访问 http://localhost:9090/alerts

---

## 六、常见问题

### 1. Prometheus 显示服务 DOWN

**原因**：服务未启动或 Actuator 未暴露

**解决**：
```bash
# 检查服务是否运行
curl http://localhost:8100/actuator/health

# 检查 Prometheus 端点
curl http://localhost:8100/actuator/prometheus

# 如果返回 404，检查 application.yml 配置：
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
```

### 2. Grafana 无数据

**原因**：
- Prometheus 数据源未配置
- 服务未产生指标数据

**解决**：
```bash
# 1. 检查 Prometheus 是否有数据
curl http://localhost:9090/api/v1/query?query=ai_chat_requests_total

# 2. 触发一些请求产生数据
curl -X POST http://localhost:8100/api/chat \
  -H "Content-Type: application/json" \
  -d '{"customerId":"test","message":"你好"}'

# 3. 在 Grafana 中刷新仪表盘
```

### 3. 指标名称不匹配

**原因**：Micrometer 会自动转换指标名称

**规则**：
- Java: `ai.chat.requests` → Prometheus: `ai_chat_requests_total`
- 驼峰命名 → 下划线命名
- Counter 自动添加 `_total` 后缀

### 4. 重新加载配置

```bash
# 重新加载 Prometheus 配置（无需重启）
curl -X POST http://localhost:9090/-/reload

# 或者重启容器
docker compose restart prometheus grafana
```

---

## 七、性能优化建议

### 1. 调整抓取间隔

如果服务较多，可以调整抓取间隔：

```yaml
# docker/prometheus/prometheus.yml
global:
  scrape_interval: 30s  # 从 15s 改为 30s
```

### 2. 数据保留时间

默认保留 15 天，可以调整：

```yaml
# docker-compose.yml
prometheus:
  command:
    - '--storage.tsdb.retention.time=30d'  # 保留 30 天
```

### 3. 限制指标数量

只暴露必要的指标：

```yaml
# application.yml
management:
  metrics:
    enable:
      jvm: true
      process: true
      system: true
      tomcat: false  # 禁用不需要的指标
```

---

## 八、下一步

1. **启动服务并产生数据**
   ```bash
   # 启动 ai-chat-service
   mvn -q -pl ai-chat-service spring-boot:run
   
   # 发送测试请求
   curl -X POST http://localhost:8100/api/chat \
     -H "Content-Type: application/json" \
     -d '{"customerId":"test","message":"测试消息"}'
   ```

2. **查看 Grafana 仪表盘**
   - 访问 http://localhost:3000
   - 打开 "AI Chat Service 监控" 仪表盘
   - 观察指标变化

3. **配置告警**（可选）
   - 创建 `alerts.yml`
   - 配置 AlertManager（钉钉/邮件通知）

4. **为其他服务创建仪表盘**
   - 参考 `ai-chat-service.json`
   - 创建 Order Service、Payment Service 等仪表盘

---

## 九、相关资源

- **Prometheus 文档**：https://prometheus.io/docs/
- **Grafana 文档**：https://grafana.com/docs/
- **PromQL 教程**：https://prometheus.io/docs/prometheus/latest/querying/basics/
- **Spring Boot Actuator**：https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html
