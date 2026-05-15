# Elasticsearch 集成指南

## 概述

本文档描述了 ai-chat-service 中对话历史的 Elasticsearch 集成实现，包括部署、配置、测试和验证步骤。

## 架构设计

### 数据流

```
用户对话 -> MySQL (主存储) -> RabbitMQ (异步消息) -> Elasticsearch (搜索层)
                                                              ↓
                                                         搜索/推荐 API
```

### 核心组件

1. **ChatHistoryDocument**: ES 文档实体，映射对话历史数据
2. **ChatHistorySearchService**: 搜索服务，提供全文搜索和相似对话推荐
3. **ChatHistoryEsSyncConsumer**: RabbitMQ 消费者，异步同步数据到 ES
4. **ChatSearchController**: REST API，提供搜索接口
5. **AiChatService**: 核心服务，集成 ES 同步和自动推荐

## 部署步骤

### 1. 启动 Elasticsearch 和 Kibana

```bash
cd /Users/lejinbo/LLM/code-snippets/governance-suite
docker-compose up -d elasticsearch kibana
```

验证服务状态：
```bash
# 检查 Elasticsearch
curl http://localhost:9200

# 检查 Kibana
open http://localhost:5601
```

### 2. 安装 IK 中文分词器

```bash
# 进入 Elasticsearch 容器
docker exec -it governance-suite-elasticsearch-1 bash

# 安装 IK 分词器
elasticsearch-plugin install https://github.com/medcl/elasticsearch-analysis-ik/releases/download/v8.11.0/elasticsearch-analysis-ik-8.11.0.zip

# 退出容器
exit

# 重启 Elasticsearch
docker-compose restart elasticsearch
```

验证分词器安装：
```bash
curl -X POST "localhost:9200/_analyze" -H 'Content-Type: application/json' -d'
{
  "analyzer": "ik_max_word",
  "text": "如何使用Spring Boot集成Elasticsearch"
}'
```

### 3. 创建索引

索引会在应用启动时自动创建，配置文件位于：
- `src/main/resources/elasticsearch/chat-history-settings.json`

手动创建索引（可选）：
```bash
curl -X PUT "localhost:9200/chat_history" -H 'Content-Type: application/json' -d'
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 1,
    "analysis": {
      "analyzer": {
        "ik_max_word": {
          "type": "ik_max_word"
        },
        "ik_smart": {
          "type": "ik_smart"
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "customerId": {
        "type": "keyword"
      },
      "conversationId": {
        "type": "keyword"
      },
      "userMessage": {
        "type": "text",
        "analyzer": "ik_max_word",
        "search_analyzer": "ik_smart"
      },
      "aiResponse": {
        "type": "text",
        "analyzer": "ik_max_word",
        "search_analyzer": "ik_smart"
      },
      "createTime": {
        "type": "date",
        "format": "date_time"
      }
    }
  }
}'
```

### 4. 配置应用

编辑 `application.yml`：

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200
    connection-timeout: 5s
    socket-timeout: 30s
  
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    listener:
      simple:
        retry:
          enabled: true
          initial-interval: 3000
          max-attempts: 3
          multiplier: 2.0
          max-interval: 10000

ai-chat:
  chat-history:
    sync-enabled: true          # 启用 ES 同步
    search-enabled: true         # 启用搜索功能
    auto-recommend-enabled: true # 启用自动推荐
    recommend-count: 3           # 推荐数量
```

### 5. 启动应用

```bash
cd ai-chat-service
mvn spring-boot:run
```

## 功能验证

### 1. 验证数据同步

发起一次对话：
```bash
curl -X POST "http://localhost:8084/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test-customer-001",
    "message": "如何使用 Spring Boot 集成 Elasticsearch？",
    "conversationId": null
  }'
```

等待 2-3 秒后，检查 ES 中的数据：
```bash
curl -X GET "localhost:9200/chat_history/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "query": {
    "match": {
      "customerId": "test-customer-001"
    }
  }
}'
```

### 2. 验证搜索功能

使用搜索 API：
```bash
curl -X GET "http://localhost:8084/api/chat/search/similar?customerId=test-customer-001&query=Spring%20Boot&limit=5"
```

### 3. 验证自动推荐

发起新对话时，响应中会包含 `recommendations` 字段：
```bash
curl -X POST "http://localhost:8084/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test-customer-001",
    "message": "Elasticsearch 的性能如何优化？",
    "conversationId": null
  }'
```

响应示例：
```json
{
  "success": true,
  "message": "...",
  "conversationId": "...",
  "recommendations": [
    {
      "question": "如何使用 Spring Boot 集成 Elasticsearch？",
      "answer": "...",
      "conversationId": "..."
    }
  ]
}
```

### 4. 使用 Kibana 查看数据

1. 访问 http://localhost:5601
2. 进入 Dev Tools
3. 执行查询：

```json
GET /chat_history/_search
{
  "query": {
    "match_all": {}
  }
}
```

## 运行测试

### 单元测试

```bash
mvn -pl ai-chat-service test -Dtest=ChatHistorySearchServiceTest
```

### 集成测试

需要先启动 Elasticsearch 和 RabbitMQ：
```bash
docker-compose up -d elasticsearch rabbitmq

# 运行集成测试
mvn -pl ai-chat-service test -Dtest=ChatHistoryEsIntegrationTest
```

## 监控和维护

### 1. 查看索引状态

```bash
# 查看所有索引
curl -X GET "localhost:9200/_cat/indices?v"

# 查看 chat_history 索引详情
curl -X GET "localhost:9200/chat_history/_stats?pretty"
```

### 2. 查看 RabbitMQ 队列

访问 RabbitMQ 管理界面：http://localhost:15672
- 用户名：guest
- 密码：guest

检查队列：`ai.chat.history.sync`

### 3. 查看应用日志

```bash
# 查看 ES 同步日志
tail -f logs/ai-chat-service.log | grep "ChatHistoryEsSyncConsumer"

# 查看搜索日志
tail -f logs/ai-chat-service.log | grep "ChatHistorySearchService"
```

### 4. 性能监控

使用 Kibana 的 Monitoring 功能监控 ES 性能：
- 索引速率
- 搜索延迟
- 磁盘使用
- JVM 堆内存

## 故障排查

### 问题 1: 数据未同步到 ES

**症状**: 对话保存成功，但 ES 中查询不到数据

**排查步骤**:
1. 检查 RabbitMQ 队列是否有消息积压
2. 检查应用日志是否有消费异常
3. 检查 ES 连接是否正常
4. 验证索引是否存在

**解决方法**:
```bash
# 检查队列
curl -u guest:guest http://localhost:15672/api/queues/%2F/ai.chat.history.sync

# 检查 ES 连接
curl http://localhost:9200/_cluster/health

# 检查索引
curl http://localhost:9200/_cat/indices?v
```

### 问题 2: 搜索结果不准确

**症状**: 搜索返回不相关的结果

**排查步骤**:
1. 检查分词器是否正确安装
2. 验证字段映射是否正确
3. 测试分词效果

**解决方法**:
```bash
# 测试分词
curl -X POST "localhost:9200/_analyze" -H 'Content-Type: application/json' -d'
{
  "analyzer": "ik_max_word",
  "text": "你的搜索关键词"
}'

# 查看索引映射
curl -X GET "localhost:9200/chat_history/_mapping?pretty"
```

### 问题 3: 消息消费失败

**症状**: RabbitMQ 队列消息不断重试

**排查步骤**:
1. 查看应用日志中的异常堆栈
2. 检查消息格式是否正确
3. 验证 ES 写入权限

**解决方法**:
- 修复数据格式问题
- 增加重试次数和间隔
- 配置死信队列处理失败消息

## 性能优化建议

### 1. ES 配置优化

```yaml
# 增加批量写入大小
spring:
  data:
    elasticsearch:
      repositories:
        enabled: true
      client:
        reactive:
          max-in-memory-size: 10MB
```

### 2. 索引优化

- 调整分片数量（根据数据量）
- 配置索引生命周期管理（ILM）
- 定期清理旧数据

### 3. 搜索优化

- 使用缓存减少重复查询
- 限制返回字段（_source filtering）
- 使用分页避免深度分页

### 4. 同步优化

- 批量发送消息到 RabbitMQ
- 增加消费者并发数
- 使用批量写入 ES

## 扩展功能

### 1. 高级搜索

实现更复杂的搜索功能：
- 多字段搜索
- 模糊搜索
- 范围查询
- 聚合统计

### 2. 搜索建议

基于用户输入提供搜索建议：
- 自动补全
- 拼写纠正
- 热门搜索

### 3. 个性化推荐

基于用户历史优化推荐：
- 协同过滤
- 内容相似度
- 时间衰减

## 参考资料

- [Spring Data Elasticsearch 文档](https://docs.spring.io/spring-data/elasticsearch/docs/current/reference/html/)
- [Elasticsearch 官方文档](https://www.elastic.co/guide/en/elasticsearch/reference/8.11/index.html)
- [IK 分词器](https://github.com/medcl/elasticsearch-analysis-ik)
- [RabbitMQ 文档](https://www.rabbitmq.com/documentation.html)
