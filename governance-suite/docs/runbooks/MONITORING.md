# Prometheus + Grafana 监控集成完成

## ✅ 已完成的工作

### 1. Docker Compose 配置
- ✅ 添加 Prometheus 服务（端口 9090）
- ✅ 添加 Grafana 服务（端口 3000）
- ✅ 配置健康检查和自动重启
- ✅ 添加数据持久化卷

### 2. Prometheus 配置
- ✅ 创建 `docker/prometheus/prometheus.yml`
- ✅ 配置 7 个微服务监控目标：
  - ai-chat-service (8100)
  - gateway (8080)
  - user-service (8084)
  - order-service (8081)
  - payment-service (8082)
  - inventory-service (8083)
  - outbox-consumer (8090)
- ✅ 设置 15 秒抓取间隔

### 3. Grafana 配置
- ✅ 自动配置 Prometheus 数据源
- ✅ 创建 AI Chat Service 监控仪表盘，包含 6 个面板：
  1. AI Chat QPS（每秒请求数）
  2. 总请求数
  3. 响应时间分布（P50/P95/P99）
  4. 缓存命中率（上下文/画像）
  5. 异常指标监控（去重/压缩）
  6. 各组件平均耗时
- ✅ 配置自动加载仪表盘

### 4. 文档和工具
- ✅ 创建详细使用指南 `docker/prometheus/README.md`
- ✅ 创建验证脚本 `docker/prometheus/verify.sh`

---

## 🚀 快速开始

### 启动监控服务
```bash
cd /Users/lejinbo/LLM/code-snippets/governance-suite
docker compose up -d prometheus grafana
```

### 验证安装
```bash
./docker/prometheus/verify.sh
```

### 访问界面
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin)

---

## 📊 当前状态

### 容器运行状态
```
✓ gov-prometheus  运行正常 (健康)
✓ gov-grafana     运行正常 (健康)
```

### 监控目标状态
所有应用服务当前显示 `down`，这是正常的，因为它们还未启动。

---

## 📝 下一步操作

### 1. 启动 ai-chat-service 并验证指标
```bash
# 启动服务
mvn -q -pl ai/ai-chat-service spring-boot:run

# 等待服务启动后，发送测试请求
curl -X POST http://localhost:8100/api/chat \
  -H "Content-Type: application/json" \
  -d '{"customerId":"test","message":"你好"}'

# 验证指标
curl http://localhost:8100/actuator/prometheus | grep "ai_chat"
```

### 2. 查看 Grafana 仪表盘
1. 访问 http://localhost:3000
2. 登录（admin/admin）
3. 导航到 **Dashboards** → **Browse**
4. 打开 **AI Chat Service 监控**
5. 观察指标变化

### 3. 在 Prometheus 中查询指标
访问 http://localhost:9090，尝试以下查询：
```promql
# QPS
rate(ai_chat_requests_total[1m])

# P95 响应时间
histogram_quantile(0.95, rate(ai_chat_response_time_seconds_bucket[5m]))

# 缓存命中率
ai_context_cache_hit_total / (ai_context_cache_hit_total + ai_context_cache_miss_total) * 100
```

### 4. 为其他服务添加监控（可选）
参考 `docker/grafana/provisioning/dashboards/ai-chat-service.json`，为其他微服务创建仪表盘。

### 5. 配置告警（可选）
参考 `docker/prometheus/README.md` 中的告警配置章节。

---

## 📂 文件结构

```
governance-suite/
├── docker-compose.yml                          # 已添加 prometheus 和 grafana 服务
└── docker/
    ├── prometheus/
    │   ├── prometheus.yml                      # Prometheus 配置
    │   ├── README.md                           # 详细使用指南
    │   └── verify.sh                           # 验证脚本
    └── grafana/
        └── provisioning/
            ├── datasources/
            │   └── prometheus.yml              # Prometheus 数据源配置
            └── dashboards/
                ├── dashboard.yml               # 仪表盘加载配置
                └── ai-chat-service.json        # AI Chat 监控仪表盘
```

---

## 🔧 配置说明

### Prometheus 配置要点
- **抓取间隔**: 15 秒
- **目标地址**: 使用 `host.docker.internal` 访问宿主机服务
- **标签**: 每个服务都有 `application` 和 `service` 标签

### Grafana 配置要点
- **默认账号**: admin/admin
- **数据源**: 自动配置 Prometheus
- **仪表盘**: 自动加载 AI Chat Service 监控
- **刷新间隔**: 5 秒

### 应用服务要求
确保每个服务的 `application.yml` 包含：
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

---

## 🆚 与 ELK 的关系

| 功能 | Prometheus + Grafana | ELK Stack |
|------|---------------------|-----------|
| **用途** | 指标监控（Metrics） | 日志分析（Logs） |
| **数据** | 时序数值 | 文本日志 |
| **场景** | QPS、延迟、缓存命中率 | 错误日志、操作轨迹 |
| **告警** | ✅ 原生支持 | ⚠️ 需要插件 |

**建议**: 两者配合使用
- Prometheus 发现性能异常 → ELK 查找错误日志定位原因
- 构建完整的可观测性体系（Metrics + Logs + Traces）

---

## 📚 参考文档

- **详细使用指南**: `docker/prometheus/README.md`
- **Prometheus 官方文档**: https://prometheus.io/docs/
- **Grafana 官方文档**: https://grafana.com/docs/
- **PromQL 教程**: https://prometheus.io/docs/prometheus/latest/querying/basics/

---

## ✨ 总结

你的项目现在已经具备完整的监控能力：

1. ✅ **Prometheus** 收集指标
2. ✅ **Grafana** 可视化展示
3. ✅ **预置仪表盘** 开箱即用
4. ✅ **7 个微服务** 已配置监控
5. ✅ **详细文档** 和验证工具

只需启动应用服务，就能立即看到实时监控数据！
