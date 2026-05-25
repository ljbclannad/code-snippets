# Kibana Dashboard 使用指南

## 访问信息

- **Kibana URL**: http://localhost:5601
- **Elasticsearch URL**: http://localhost:9200
- **Index Pattern**: `governance-*`
- **Dashboard**: Governance Suite Monitoring

## 已配置内容

### 1. Data View (Index Pattern)
- **名称**: Governance Suite Logs
- **Pattern**: `governance-*`
- **Time Field**: `@timestamp`
- **ID**: `cbfe6fd5-7f21-4bf2-bfb9-2405e13b4fe5`

### 2. 可视化组件

#### 2.1 Order Creation Trend (订单创建趋势)
- **类型**: Line Chart
- **指标**: Count
- **时间范围**: Last 15 minutes
- **过滤条件**: `event:order_created`
- **用途**: 监控订单创建的时间趋势

#### 2.2 Service Request Distribution (服务请求分布)
- **类型**: Pie Chart (Donut)
- **指标**: Count by service.keyword
- **用途**: 查看各个微服务的请求分布情况

#### 2.3 Error Rate (错误率监控)
- **类型**: Line Chart
- **指标**: Count
- **时间范围**: Last 15 minutes
- **过滤条件**: `level:ERROR`
- **用途**: 实时监控系统错误率

#### 2.4 Payment Status Distribution (支付状态分布)
- **类型**: Pie Chart (Donut)
- **指标**: Count by status.keyword
- **过滤条件**: `event:payment_*`
- **用途**: 监控支付事件的状态分布

### 3. Dashboard
- **名称**: Governance Suite Monitoring
- **布局**: 2x2 网格
- **描述**: 业务指标监控仪表板

## 快速访问

### 方式 1: 直接访问 Dashboard
```bash
open http://localhost:5601/app/dashboards#/view/governance-suite-dashboard
```

### 方式 2: 通过 UI 导航
1. 访问 http://localhost:5601
2. 点击左侧菜单 "Dashboard"
3. 选择 "Governance Suite Monitoring"

### 方式 3: 使用 Discover 探索数据
1. 访问 http://localhost:5601
2. 点击左侧菜单 "Discover"
3. 选择 "Governance Suite Logs" data view
4. 设置时间范围 (右上角)
5. 使用搜索栏进行查询

## 常用查询示例

### 按服务过滤
```
service:order-service
service:payment-service
service:inventory-service
```

### 按事件类型过滤
```
event:order_created
event:payment_prepared
event:payment_confirmed
event:inventory_deducted
```

### 按日志级别过滤
```
level:INFO
level:WARN
level:ERROR
```

### 组合查询
```
service:order-service AND level:ERROR
event:payment_* AND status:FAILED
service:inventory-service AND event:stock_deducted
```

### 按 traceId 追踪请求链路
```
traceId:"your-trace-id-here"
```

## 自定义可视化

### 创建新的可视化
1. 进入 Dashboard
2. 点击 "Edit"
3. 点击 "Create visualization"
4. 选择可视化类型 (Line, Bar, Pie, Metric, etc.)
5. 配置 Metrics 和 Buckets
6. 保存并添加到 Dashboard

### 常用可视化配置

#### Metric: 实时订单数
- Visualization: Metric
- Metrics: Count
- Filter: `event:order_created`
- Time range: Last 1 hour

#### Table: 错误日志详情
- Visualization: Table
- Metrics: Count
- Buckets: 
  - Terms on service.keyword
  - Terms on message.keyword
- Filter: `level:ERROR`

#### Heatmap: 服务调用热力图
- Visualization: Heatmap
- Metrics: Count
- Buckets:
  - Date Histogram on @timestamp
  - Terms on service.keyword

## 告警配置 (可选)

### 创建错误率告警
1. 导航到 Stack Management > Rules and Connectors
2. 点击 "Create rule"
3. 配置规则:
   - **Name**: High Error Rate Alert
   - **Rule type**: Elasticsearch query
   - **Index**: governance-*
   - **Time field**: @timestamp
   - **Query**: `level:ERROR`
   - **Threshold**: Count > 10 in last 5 minutes
4. 配置 Actions (Email, Slack, Webhook, etc.)
5. 保存并激活

### 创建支付失败告警
1. 创建新规则
2. 配置:
   - **Name**: Payment Failure Alert
   - **Query**: `event:payment_prepared AND status:FAILED`
   - **Threshold**: Count > 5 in last 10 minutes
3. 配置通知方式

## 数据保留策略

当前配置使用日期索引: `governance-logs-YYYY.MM.DD`

### 查看索引大小
```bash
curl -s 'http://localhost:9200/_cat/indices/governance-*?v&h=index,docs.count,store.size'
```

### 删除旧索引 (示例: 删除 7 天前的数据)
```bash
# 查看要删除的索引
curl -s 'http://localhost:9200/_cat/indices/governance-logs-2026.05.15?v'

# 删除索引
curl -X DELETE 'http://localhost:9200/governance-logs-2026.05.15'
```

### 配置 ILM (Index Lifecycle Management) - 可选
1. 导航到 Stack Management > Index Lifecycle Policies
2. 创建新策略:
   - Hot phase: 保留 7 天
   - Delete phase: 删除 30 天后的数据
3. 应用到 `governance-*` 索引模板

## 性能优化建议

### 1. 时间范围选择
- 避免查询过长的时间范围 (> 7 天)
- 使用 "Last 15 minutes" 或 "Last 1 hour" 进行实时监控
- 使用 "Absolute" 时间范围进行历史分析

### 2. 查询优化
- 使用 `.keyword` 字段进行精确匹配和聚合
- 避免使用通配符开头的查询 (`*error`)
- 使用过滤器 (Filter) 而不是查询 (Query) 提高性能

### 3. 可视化优化
- 限制 Terms aggregation 的 size (默认 10)
- 使用 Date Histogram 的 auto interval
- 避免在单个 Dashboard 中放置过多可视化 (建议 < 10)

## 故障排查

### Dashboard 无数据显示
1. 检查时间范围是否正确
2. 验证索引中是否有数据:
   ```bash
   curl -s 'http://localhost:9200/governance-*/_count'
   ```
3. 检查过滤条件是否过于严格
4. 刷新 Data View 字段列表

### 可视化显示错误
1. 检查字段映射是否正确
2. 验证聚合字段是否存在
3. 查看浏览器控制台错误信息
4. 重新创建可视化

### Kibana 无法连接 Elasticsearch
```bash
# 检查 Elasticsearch 状态
curl http://localhost:9200/_cluster/health?pretty

# 检查 Kibana 日志
docker logs elk-kibana-1
```

## 扩展功能

### 1. Canvas (报表制作)
- 创建自定义报表和演示文稿
- 支持导出 PDF

### 2. Machine Learning (异常检测)
- 自动检测日志中的异常模式
- 预测未来趋势

### 3. APM (应用性能监控)
- 集成 Elastic APM Agent
- 追踪分布式事务

### 4. Uptime Monitoring (可用性监控)
- 监控服务端点可用性
- 配置 HTTP/TCP 检查

## 相关文档

- [Elasticsearch 查询 DSL](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl.html)
- [Kibana 可视化指南](https://www.elastic.co/guide/en/kibana/current/dashboard.html)
- [Logstash 配置参考](https://www.elastic.co/guide/en/logstash/current/configuration.html)
- [ELK Stack 最佳实践](https://www.elastic.co/guide/en/elasticsearch/reference/current/best-practices.html)