# 可观测性增强实施计划 - Part 3: 基础设施部署

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 部署 Jaeger 追踪后端，创建 Prometheus 告警规则，更新 docker-compose 配置

**Architecture:** 使用 Jaeger All-in-One 镜像（内存存储），按服务拆分告警规则文件，Prometheus 自动加载规则

**Tech Stack:**
- Jaeger 1.55
- Prometheus Rules
- Docker Compose

---

## Task 1: 添加 Jaeger 到 Docker Compose

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: 在 docker-compose.yml 中添加 Jaeger 服务**

在 `docker-compose.yml` 的 `services` 部分，在 `grafana` 之后添加：

```yaml
    jaeger:
        image: jaegertracing/all-in-one:1.55
        container_name: gov-jaeger
        restart: unless-stopped
        environment:
            - COLLECTOR_OTLP_ENABLED=true
            - SPAN_STORAGE_TYPE=memory
            - MEMORY_MAX_TRACES=10000
        ports:
            - "16686:16686"  # Jaeger UI
            - "4317:4317"    # OTLP gRPC
            - "4318:4318"    # OTLP HTTP
        healthcheck:
            test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:16686/"]
            interval: 30s
            timeout: 10s
            retries: 3
```

- [ ] **Step 2: 验证 YAML 格式**

运行：`docker compose config > /dev/null`

预期输出：无错误（格式正确）

- [ ] **Step 3: 启动 Jaeger**

运行：`docker compose up -d jaeger`

预期输出：`Container gov-jaeger  Started`

- [ ] **Step 4: 验证 Jaeger 可访问**

等待 30 秒后运行：`curl -s http://localhost:16686/ | grep -i jaeger`

预期输出：包含 "Jaeger" 字样的 HTML

在浏览器访问：http://localhost:16686

预期：看到 Jaeger UI 界面

- [ ] **Step 5: 提交 Jaeger 配置**

```bash
git add docker-compose.yml
git commit -m "feat(infra): add Jaeger all-in-one for distributed tracing

- Enable OTLP collector on port 4317
- Use memory storage with max 10000 traces
- Expose Jaeger UI on port 16686
- Add healthcheck for service readiness"
```

---

## Task 2: 创建告警规则目录结构

**Files:**
- Create: `docker/prometheus/alerts/` (directory)

- [ ] **Step 1: 创建告警规则目录**

运行：`mkdir -p docker/prometheus/alerts`

- [ ] **Step 2: 验证目录创建**

运行：`ls -la docker/prometheus/`

预期输出：包含 `alerts` 目录

- [ ] **Step 3: 提交目录结构**

```bash
git add docker/prometheus/alerts/.gitkeep
touch docker/prometheus/alerts/.gitkeep
git add docker/prometheus/alerts/.gitkeep
git commit -m "chore(infra): create Prometheus alerts directory"
```

---

## Task 3: 创建通用告警规则

**Files:**
- Create: `docker/prometheus/alerts/common-alerts.yml`

- [ ] **Step 1: 创建通用告警规则文件**

创建文件 `docker/prometheus/alerts/common-alerts.yml`：

```yaml
groups:
  - name: common
    interval: 30s
    rules:
      # 服务下线
      - alert: ServiceDown
        expr: up{job=~".*-service|gateway"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "服务 {{ $labels.job }} 下线"
          description: "{{ $labels.instance }} 已下线超过 1 分钟"

      # HTTP 错误率过高
      - alert: HighHttpErrorRate
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (application)
          /
          sum(rate(http_server_requests_seconds_count[5m])) by (application)
          > 0.05
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} HTTP 错误率过高"
          description: "5xx 错误率 {{ $value | humanizePercentage }}"

      # HTTP 响应时间过长
      - alert: HighHttpLatency
        expr: |
          histogram_quantile(0.99, 
            sum(rate(http_server_requests_seconds_bucket[5m])) by (application, le)
          ) > 3
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} HTTP P99 延迟过高"
          description: "P99 延迟 {{ $value | humanizeDuration }}"

      # JVM 堆内存使用率过高
      - alert: HighJvmMemoryUsage
        expr: |
          jvm_memory_used_bytes{area="heap"} 
          / 
          jvm_memory_max_bytes{area="heap"} 
          > 0.85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} JVM 堆内存使用率过高"
          description: "堆内存使用率 {{ $value | humanizePercentage }}"

      # GC 时间占比过高
      - alert: HighGcTime
        expr: |
          rate(jvm_gc_pause_seconds_sum[5m]) 
          / 
          rate(jvm_gc_pause_seconds_count[5m]) 
          > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} GC 时间占比过高"
          description: "平均 GC 时间 {{ $value | humanizeDuration }}"

      # 线程数过多
      - alert: HighThreadCount
        expr: jvm_threads_live > 500
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} 线程数过多"
          description: "当前线程数 {{ $value }}"

      # 数据库连接池耗尽
      - alert: DatabaseConnectionPoolExhausted
        expr: |
          hikaricp_connections_active 
          / 
          hikaricp_connections_max 
          > 0.9
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "{{ $labels.application }} 数据库连接池接近耗尽"
          description: "连接池使用率 {{ $value | humanizePercentage }}"
```

- [ ] **Step 2: 验证 YAML 格式**

运行：`docker run --rm -v $(pwd)/docker/prometheus/alerts:/alerts prom/prometheus:latest promtool check rules /alerts/common-alerts.yml`

预期输出：`SUCCESS: ... rules found`

- [ ] **Step 3: 提交通用告警规则**

```bash
git add docker/prometheus/alerts/common-alerts.yml
git commit -m "feat(monitoring): add common alert rules

- ServiceDown: detect service outages
- HighHttpErrorRate: 5xx error rate > 5%
- HighHttpLatency: P99 latency > 3s
- HighJvmMemoryUsage: heap usage > 85%
- HighGcTime: GC time ratio > 10%
- HighThreadCount: thread count > 500
- DatabaseConnectionPoolExhausted: connection pool > 90%"
```

---

## Task 4: 创建 Gateway 告警规则

**Files:**
- Create: `docker/prometheus/alerts/gateway-alerts.yml`

- [ ] **Step 1: 创建 Gateway 告警规则**

创建文件 `docker/prometheus/alerts/gateway-alerts.yml`：

```yaml
groups:
  - name: gateway
    interval: 30s
    rules:
      # 限流拒绝率过高
      - alert: HighRateLimitRejection
        expr: |
          rate(gateway_ratelimit_rejected_total[5m]) > 10
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Gateway 限流拒绝率过高"
          description: "每秒拒绝 {{ $value }} 个请求"

      # 熔断器打开
      - alert: CircuitBreakerOpen
        expr: gateway_circuit_open == 1
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Gateway 熔断器打开"
          description: "路由 {{ $labels.route }} 的熔断器已打开"
```

- [ ] **Step 2: 验证规则**

运行：`docker run --rm -v $(pwd)/docker/prometheus/alerts:/alerts prom/prometheus:latest promtool check rules /alerts/gateway-alerts.yml`

预期输出：`SUCCESS`

- [ ] **Step 3: 提交 Gateway 告警规则**

```bash
git add docker/prometheus/alerts/gateway-alerts.yml
git commit -m "feat(monitoring): add gateway alert rules

- HighRateLimitRejection: rate limit > 10 req/s
- CircuitBreakerOpen: circuit breaker opened"
```

---

## Task 5: 创建 Order Service 告警规则

**Files:**
- Create: `docker/prometheus/alerts/order-service-alerts.yml`

- [ ] **Step 1: 创建 Order Service 告警规则**

创建文件 `docker/prometheus/alerts/order-service-alerts.yml`：

```yaml
groups:
  - name: order-service
    interval: 30s
    rules:
      # 订单创建失败率过高
      - alert: HighOrderFailureRate
        expr: |
          sum(rate(order_create_total{status="failure"}[5m]))
          /
          sum(rate(order_create_total[5m]))
          > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "订单创建失败率过高"
          description: "失败率 {{ $value | humanizePercentage }}"

      # Seata 事务回滚率过高
      - alert: HighSeataRollbackRate
        expr: |
          sum(rate(seata_transaction_total{status="rollback"}[5m]))
          /
          sum(rate(seata_transaction_total[5m]))
          > 0.2
        for: 3m
        labels:
          severity: critical
        annotations:
          summary: "Seata 分布式事务回滚率过高"
          description: "回滚率 {{ $value | humanizePercentage }}"

      # 秒杀库存耗尽
      - alert: SeckillStockDepleted
        expr: seckill_stock_remaining == 0
        for: 30s
        labels:
          severity: info
        annotations:
          summary: "秒杀商品库存耗尽"
          description: "SKU {{ $labels.sku_id }} 库存已清空"

      # 订单创建耗时过长
      - alert: SlowOrderCreation
        expr: |
          histogram_quantile(0.99, 
            sum(rate(order_create_duration_bucket[5m])) by (le)
          ) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "订单创建耗时过长"
          description: "P99 耗时 {{ $value | humanizeDuration }}"
```

- [ ] **Step 2: 验证规则**

运行：`docker run --rm -v $(pwd)/docker/prometheus/alerts:/alerts prom/prometheus:latest promtool check rules /alerts/order-service-alerts.yml`

预期输出：`SUCCESS`

- [ ] **Step 3: 提交 Order Service 告警规则**

```bash
git add docker/prometheus/alerts/order-service-alerts.yml
git commit -m "feat(monitoring): add order-service alert rules

- HighOrderFailureRate: failure rate > 10%
- HighSeataRollbackRate: rollback rate > 20%
- SeckillStockDepleted: seckill stock depleted
- SlowOrderCreation: P99 latency > 5s"
```

---

## Task 6: 创建 Payment Service 告警规则

**Files:**
- Create: `docker/prometheus/alerts/payment-service-alerts.yml`

- [ ] **Step 1: 创建 Payment Service 告警规则**

创建文件 `docker/prometheus/alerts/payment-service-alerts.yml`：

```yaml
groups:
  - name: payment-service
    interval: 30s
    rules:
      # 支付成功率过低
      - alert: LowPaymentSuccessRate
        expr: |
          sum(rate(payment_confirm_total{status="success"}[5m]))
          /
          sum(rate(payment_confirm_total[5m]))
          < 0.95
        for: 3m
        labels:
          severity: critical
        annotations:
          summary: "支付成功率过低"
          description: "成功率 {{ $value | humanizePercentage }}"

      # 支付预处理失败率过高
      - alert: HighPaymentPrepareFailureRate
        expr: |
          sum(rate(payment_prepare_total{status="failure"}[5m]))
          /
          sum(rate(payment_prepare_total[5m]))
          > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "支付预处理失败率过高"
          description: "失败率 {{ $value | humanizePercentage }}"
```

- [ ] **Step 2: 验证规则**

运行：`docker run --rm -v $(pwd)/docker/prometheus/alerts:/alerts prom/prometheus:latest promtool check rules /alerts/payment-service-alerts.yml`

预期输出：`SUCCESS`

- [ ] **Step 3: 提交 Payment Service 告警规则**

```bash
git add docker/prometheus/alerts/payment-service-alerts.yml
git commit -m "feat(monitoring): add payment-service alert rules

- LowPaymentSuccessRate: success rate < 95%
- HighPaymentPrepareFailureRate: prepare failure rate > 10%"
```

---

## Task 7: 创建 Inventory Service 告警规则

**Files:**
- Create: `docker/prometheus/alerts/inventory-alerts.yml`

- [ ] **Step 1: 创建 Inventory Service 告警规则**

创建文件 `docker/prometheus/alerts/inventory-alerts.yml`：

```yaml
groups:
  - name: inventory-service
    interval: 30s
    rules:
      # 库存扣减失败率过高
      - alert: HighInventoryDeductFailureRate
        expr: |
          sum(rate(inventory_deduct_total{status="failure"}[5m]))
          /
          sum(rate(inventory_deduct_total[5m]))
          > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "库存扣减失败率过高"
          description: "失败率 {{ $value | humanizePercentage }}"

      # 乐观锁重试次数过多
      - alert: HighInventoryRetryCount
        expr: rate(inventory_deduct_retry_total[5m]) > 5
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "库存扣减乐观锁重试次数过多"
          description: "每秒重试 {{ $value }} 次，可能存在高并发冲突"
```

- [ ] **Step 2: 验证规则**

运行：`docker run --rm -v $(pwd)/docker/prometheus/alerts:/alerts prom/prometheus:latest promtool check rules /alerts/inventory-alerts.yml`

预期输出：`SUCCESS`

- [ ] **Step 3: 提交 Inventory Service 告警规则**

```bash
git add docker/prometheus/alerts/inventory-alerts.yml
git commit -m "feat(monitoring): add inventory-service alert rules

- HighInventoryDeductFailureRate: failure rate > 10%
- HighInventoryRetryCount: retry rate > 5/s"
```

---

## Task 8: 创建 Outbox Consumer 告警规则

**Files:**
- Create: `docker/prometheus/alerts/outbox-alerts.yml`

- [ ] **Step 1: 创建 Outbox Consumer 告警规则**

创建文件 `docker/prometheus/alerts/outbox-alerts.yml`：

```yaml
groups:
  - name: outbox
    interval: 30s
    rules:
      # Outbox 发布延迟过高
      - alert: HighOutboxPublishLag
        expr: outbox_publish_lag > 60
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Outbox 发布延迟过高"
          description: "延迟 {{ $value | humanizeDuration }}"

      # DEAD 消息数增长
      - alert: OutboxDeadMessagesIncreasing
        expr: increase(outbox_dead_total[10m]) > 5
        labels:
          severity: critical
        annotations:
          summary: "Outbox DEAD 消息数增长"
          description: "10 分钟内新增 {{ $value }} 条 DEAD 消息"

      # Outbox 发布失败率过高
      - alert: HighOutboxPublishFailureRate
        expr: |
          sum(rate(outbox_publish_total{status="failure"}[5m]))
          /
          sum(rate(outbox_publish_total[5m]))
          > 0.2
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "Outbox 发布失败率过高"
          description: "失败率 {{ $value | humanizePercentage }}"
```

- [ ] **Step 2: 验证规则**

运行：`docker run --rm -v $(pwd)/docker/prometheus/alerts:/alerts prom/prometheus:latest promtool check rules /alerts/outbox-alerts.yml`

预期输出：`SUCCESS`

- [ ] **Step 3: 提交 Outbox Consumer 告警规则**

```bash
git add docker/prometheus/alerts/outbox-alerts.yml
git commit -m "feat(monitoring): add outbox-consumer alert rules

- HighOutboxPublishLag: publish lag > 60s
- OutboxDeadMessagesIncreasing: dead messages > 5 in 10min
- HighOutboxPublishFailureRate: failure rate > 20%"
```

---

## Task 9: 更新 Prometheus 配置加载告警规则

**Files:**
- Modify: `docker/prometheus/prometheus.yml`
- Modify: `docker-compose.yml`

- [ ] **Step 1: 在 prometheus.yml 中添加规则文件加载**

在 `docker/prometheus/prometheus.yml` 的顶部（global 之后）添加：

```yaml
rule_files:
  - '/etc/prometheus/alerts/*.yml'
```

完整的文件开头应该是：

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
  external_labels:
    cluster: 'governance-suite'
    environment: 'development'

rule_files:
  - '/etc/prometheus/alerts/*.yml'

scrape_configs:
  # ...
```

- [ ] **Step 2: 更新 docker-compose.yml 挂载告警规则目录**

在 `docker-compose.yml` 的 `prometheus` 服务中，更新 `volumes` 和 `command`：

```yaml
    prometheus:
        image: prom/prometheus:latest
        container_name: gov-prometheus
        restart: unless-stopped
        ports:
            - "9090:9090"
        volumes:
            - ./docker/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
            - ./docker/prometheus/alerts:/etc/prometheus/alerts:ro
            - gov_prometheus_data:/prometheus
        command:
            - '--config.file=/etc/prometheus/prometheus.yml'
            - '--storage.tsdb.path=/prometheus'
            - '--storage.tsdb.retention.time=7d'
            - '--web.console.libraries=/usr/share/prometheus/console_libraries'
            - '--web.console.templates=/usr/share/prometheus/consoles'
            - '--web.enable-lifecycle'
        healthcheck:
            test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:9090/-/healthy"]
            interval: 30s
            timeout: 10s
            retries: 3
```

- [ ] **Step 3: 验证配置**

运行：`docker compose config > /dev/null`

预期输出：无错误

- [ ] **Step 4: 重启 Prometheus 加载规则**

运行：`docker compose restart prometheus`

等待 10 秒后验证：`curl -s http://localhost:9090/-/healthy`

预期输出：`Prometheus is Healthy.`

- [ ] **Step 5: 验证告警规则加载**

在浏览器访问：http://localhost:9090/rules

预期：看到 6 个规则组（common, gateway, order-service, payment-service, inventory-service, outbox）

- [ ] **Step 6: 提交 Prometheus 配置更新**

```bash
git add docker/prometheus/prometheus.yml docker-compose.yml
git commit -m "feat(monitoring): configure Prometheus to load alert rules

- Add rule_files to prometheus.yml
- Mount alerts directory in docker-compose
- Add 7-day data retention
- Enable lifecycle API for hot reload"
```

---

## Task 10: 基础设施集成测试

**Files:**
- None (testing only)

- [ ] **Step 1: 启动所有基础设施**

运行：`docker compose up -d`

预期输出：所有容器启动成功

- [ ] **Step 2: 验证 Prometheus 健康状态**

运行：`curl -s http://localhost:9090/-/healthy`

预期输出：`Prometheus is Healthy.`

- [ ] **Step 3: 验证 Prometheus Targets**

在浏览器访问：http://localhost:9090/targets

预期：看到 7 个服务的 targets（gateway, user-service, order-service, payment-service, inventory-service, outbox-consumer, ai-chat-service）

当前状态应该是 DOWN（因为服务还未启动）

- [ ] **Step 4: 验证 Prometheus 告警规则**

在浏览器访问：http://localhost:9090/rules

预期：看到 6 个规则组，共约 20 条告警规则

- [ ] **Step 5: 验证 Jaeger UI**

在浏览器访问：http://localhost:16686

预期：看到 Jaeger UI，Service 下拉框当前为空（因为服务还未启动）

- [ ] **Step 6: 验证 Grafana**

在浏览器访问：http://localhost:3000（admin/admin）

预期：可以登录，数据源 Prometheus 状态为绿色

- [ ] **Step 7: 创建基础设施验证总结提交**

```bash
git commit --allow-empty -m "test: verify observability infrastructure deployment

Verified:
- Jaeger all-in-one running on port 16686
- Prometheus loading 6 alert rule groups
- Prometheus targets configured for 7 services
- Grafana connected to Prometheus data source
- All containers healthy

Infrastructure ready for service deployment."
```

---

## 完成标准

Part 3 完成后，应满足：

✅ Jaeger 已部署并可通过 http://localhost:16686 访问
✅ 6 个告警规则文件已创建（common, gateway, order, payment, inventory, outbox）
✅ Prometheus 已配置加载告警规则
✅ docker-compose.yml 已更新挂载告警规则目录
✅ Prometheus 数据保留设置为 7 天
✅ 所有基础设施容器健康运行
✅ Prometheus Rules 页面显示所有告警规则

**下一步**: 继续 Part 4 - 业务指标埋点（各服务的自定义指标）
