# MySQL + Elasticsearch 双写方案设计文档

**日期**: 2026-05-15  
**作者**: Claude (Kiro)  
**状态**: 待审查

---

## 1. 概述

### 1.1 目标

为 `ai-chat-service` 设计并实现 MySQL + Elasticsearch 双写方案，提供对话历史的全文搜索和相似对话推荐功能。

**核心目标**：
- ✅ 保持 MySQL 作为主存储，保证数据可靠性
- ✅ 引入 Elasticsearch 作为搜索层，提供全文检索能力
- ✅ 通过 RabbitMQ 实现异步同步，解耦两个系统
- ✅ 提供独立搜索接口和自动推荐功能

### 1.2 背景

**当前状态**：
- 对话历史存储在 MySQL (`t_ai_chat_history` 表)
- 只能通过 `conversationId` 精确查询
- 无法进行全文搜索和语义相似度匹配

**用户需求**：
- 搜索历史对话中的关键词（如"失眠"、"焦虑"）
- 基于当前问题推荐相似的历史对话
- 实时同步，搜索延迟 < 1 秒

### 1.3 核心原则

1. **MySQL 为主** - 所有写入先到 MySQL，保证数据不丢失
2. **异步解耦** - 通过 RabbitMQ 异步同步到 ES，不影响主流程性能
3. **增量迁移** - 只同步新产生的对话，历史数据暂不处理
4. **降级友好** - ES 故障时搜索功能不可用，但主流程正常

---

## 2. 架构设计

### 2.1 整体架构

```
用户发送消息
    ↓
AiChatService.handleChatRequest()
    ↓
保存到 MySQL (同步)
    ↓
发送 MQ 消息 (异步)
    ↓
ChatHistoryEsSyncConsumer 消费
    ↓
同步到 Elasticsearch
    ↓
提供搜索功能
    ├─ 独立搜索接口: /api/chat/search
    ├─ 相似对话接口: /api/chat/search/similar
    └─ 自动推荐: 在对话响应中附带
```

### 2.2 核心组件

| 组件 | 职责 | 技术 |
|------|------|------|
| **MySQL** | 主存储，保证数据可靠性 | MyBatis-Plus |
| **RabbitMQ** | 解耦同步流程，异步处理 | Spring AMQP |
| **Elasticsearch** | 搜索层，全文检索和相似推荐 | Spring Data ES |
| **ChatHistoryDocument** | ES 文档实体 | @Document 注解 |
| **ChatHistorySearchRepository** | ES 数据访问层 | ElasticsearchRepository |
| **ChatHistorySearchService** | 搜索业务逻辑 | @Service |
| **ChatHistoryEsSyncConsumer** | MQ 消费者，同步到 ES | @RabbitListener |
| **ChatSearchController** | 搜索接口 | @RestController |

### 2.3 数据流向

**写入流程**：
```
AiChatService.saveConversationHistory()
    → MySQL (t_ai_chat_history)
    → RabbitMQ (chat.history.sync.queue)
    → ChatHistoryEsSyncConsumer
    → Elasticsearch (chat_history index)
```

**查询流程**：
```
用户搜索请求
    → ChatSearchController
    → ChatHistorySearchService
    → ChatHistorySearchRepository
    → Elasticsearch
    → 返回搜索结果
```

---

## 3. 基础设施配置

### 3.1 Docker Compose

在 `docker-compose.yml` 中添加 Elasticsearch 和 Kibana：

```yaml
elasticsearch:
    image: elasticsearch:8.11.0
    container_name: gov-elasticsearch
    environment:
        - discovery.type=single-node
        - xpack.security.enabled=false
        - "ES_JAVA_OPTS=-Xms512m -Xmx512m"
        - TZ=Asia/Shanghai
    ports:
        - "9200:9200"
        - "9300:9300"
    volumes:
        - gov_es_data:/usr/share/elasticsearch/data
    healthcheck:
        test: ["CMD-SHELL", "curl -f http://localhost:9200/_cluster/health || exit 1"]
        interval: 30s
        timeout: 10s
        retries: 3

# 注意：IK 分词器需要手动安装
# 启动容器后执行：
# docker exec -it gov-elasticsearch /bin/bash
# ./bin/elasticsearch-plugin install https://github.com/medcl/elasticsearch-analysis-ik/releases/download/v8.11.0/elasticsearch-analysis-ik-8.11.0.zip
# 然后重启容器：docker restart gov-elasticsearch

kibana:
    image: kibana:8.11.0
    container_name: gov-kibana
    environment:
        - ELASTICSEARCH_HOSTS=http://elasticsearch:9200
        - TZ=Asia/Shanghai
    ports:
        - "5601:5601"
    depends_on:
        elasticsearch:
            condition: service_healthy

volumes:
    # ... 现有 volumes
    gov_es_data:
```

### 3.2 Maven 依赖

在 `ai-chat-service/pom.xml` 中添加：

```xml
<!-- Elasticsearch -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```

### 3.3 Application 配置

在 `ai-chat-service/src/main/resources/application.yml` 中添加：

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200
    connection-timeout: 5s
    socket-timeout: 30s
  
  # RabbitMQ 配置（新增队列）
  rabbitmq:
    listener:
      simple:
        retry:
          enabled: true
          initial-interval: 3000
          max-attempts: 3
          multiplier: 2

# 对话历史同步配置
chat:
  history:
    sync:
      queue: chat.history.sync.queue
      exchange: chat.history.sync.exchange
      routing-key: chat.history.sync

# 健康检查
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  health:
    elasticsearch:
      enabled: true
  endpoint:
    health:
      show-details: always
```

---

## 4. 数据模型设计

### 4.1 Elasticsearch 文档实体

**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/document/ChatHistoryDocument.java`

```java
package com.lx.governance.aichat.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "chat_history")
@Setting(settingPath = "elasticsearch/chat-history-settings.json")
public class ChatHistoryDocument {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String customerId;

    @Field(type = FieldType.Keyword)
    private String conversationId;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String userMessage;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String aiResponse;

    @Field(type = FieldType.Integer)
    private Integer promptTokens;

    @Field(type = FieldType.Integer)
    private Integer completionTokens;

    @Field(type = FieldType.Integer)
    private Integer totalTokens;

    @Field(type = FieldType.Date, format = {}, pattern = "uuuu-MM-dd'T'HH:mm:ss")
    private LocalDateTime createTime;
}
```

### 4.2 索引设置

**文件**: `ai-chat-service/src/main/resources/elasticsearch/chat-history-settings.json`

```json
{
  "analysis": {
    "analyzer": {
      "ik_max_word": {
        "type": "custom",
        "tokenizer": "ik_max_word"
      },
      "ik_smart": {
        "type": "custom",
        "tokenizer": "ik_smart"
      }
    }
  }
}
```

**说明**：
- `ik_max_word`: 索引时使用，最细粒度分词
- `ik_smart`: 搜索时使用，智能分词

### 4.3 MQ 消息 DTO

**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ChatHistorySyncMessage.java`

```java
package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistorySyncMessage {
    private Long id;
    private String customerId;
    private String conversationId;
    private String userMessage;
    private String aiResponse;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private LocalDateTime createTime;
}
```

---

## 5. 核心实现

### 5.1 Elasticsearch Repository

**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/repository/ChatHistorySearchRepository.java`

```java
package com.lx.governance.aichat.repository;

import com.lx.governance.aichat.document.ChatHistoryDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatHistorySearchRepository extends ElasticsearchRepository<ChatHistoryDocument, String> {

    Page<ChatHistoryDocument> findByCustomerIdAndUserMessageContainingOrAiResponseContaining(
            String customerId, String keyword1, String keyword2, Pageable pageable);

    List<ChatHistoryDocument> findTop10ByCustomerIdOrderByCreateTimeDesc(String customerId);
}
```

### 5.2 搜索服务

**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/ChatHistorySearchService.java`

核心方法：
- `searchHistory()` - 全文搜索
- `findSimilarConversations()` - More Like This 查询
- `saveToEs()` - 保存文档到 ES

### 5.3 RabbitMQ 配置更新

**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/config/RabbitMQConfig.java`

新增：
- `CHAT_HISTORY_SYNC_QUEUE` 常量
- `chatHistorySyncQueue()` Bean
- `chatHistorySyncExchange()` Bean
- `chatHistorySyncBinding()` Bean

### 5.4 MQ 消费者

**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/consumer/ChatHistoryEsSyncConsumer.java`

职责：
- 监听 `chat.history.sync.queue`
- 转换 `ChatHistorySyncMessage` 为 `ChatHistoryDocument`
- 调用 `ChatHistorySearchService.saveToEs()`
- 失败时自动重试（最多 3 次）

---

## 6. 集成到现有流程

### 6.1 修改 AiChatService

在 `saveConversationHistory()` 方法后添加：

```java
private void sendToEsSync(AiChatHistory history) {
    try {
        ChatHistorySyncMessage message = ChatHistorySyncMessage.builder()
                .id(history.getId())
                .customerId(history.getCustomerId())
                .conversationId(history.getConversationId())
                .userMessage(history.getUserMessage())
                .aiResponse(history.getAiResponse())
                .promptTokens(history.getPromptTokens())
                .completionTokens(history.getCompletionTokens())
                .totalTokens(history.getTotalTokens())
                .createTime(history.getCreateTime())
                .build();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.CHAT_HISTORY_SYNC_EXCHANGE,
                RabbitMQConfig.CHAT_HISTORY_SYNC_ROUTING_KEY,
                message
        );

        log.debug("对话历史同步消息已发送 - id: {}", history.getId());

    } catch (Exception e) {
        log.error("发送对话历史同步消息失败 - id: {}", history.getId(), e);
        // 不抛出异常，避免影响主流程
    }
}
```

### 6.2 搜索控制器

**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/controller/ChatSearchController.java`

提供接口：
- `GET /api/chat/search` - 全文搜索
- `GET /api/chat/search/similar` - 相似对话推荐

### 6.3 自动推荐

修改 `ChatResponse` 添加 `similarConversations` 字段，在 `handleChatRequest()` 中调用 `findSimilarConversations()` 并附加到响应中。

---

## 7. 测试验证

### 7.1 单元测试

**文件**: `ai-chat-service/src/test/java/com/lx/governance/aichat/service/ChatHistorySearchServiceTest.java`

测试场景：
- 保存文档到 ES
- 全文搜索验证
- 相似对话查询验证

### 7.2 集成测试

**场景 1：完整流程**
1. 发送对话请求
2. 验证 MySQL 保存成功
3. 等待 ES 同步（约 1 秒）
4. 调用搜索接口验证

**场景 2：Kibana 验证**
1. 访问 http://localhost:5601
2. 在 Dev Tools 中执行查询
3. 验证索引结构和数据

---

## 8. 监控和故障处理

### 8.1 监控指标

- ES 健康状态：`/actuator/health`
- 同步延迟：MQ 消息消费时间
- 搜索性能：搜索接口响应时间
- 同步失败率：MQ 消费失败次数

### 8.2 故障处理

| 故障场景 | 影响 | 处理方式 |
|---------|------|---------|
| ES 服务宕机 | 搜索功能不可用，主流程正常 | MQ 消息堆积，ES 恢复后自动消费 |
| MQ 消费失败 | 部分对话未同步到 ES | 自动重试 3 次，失败后记录日志 |
| 索引损坏 | 搜索结果异常 | 删除索引重建，从 MySQL 全量迁移 |
| 网络超时 | 搜索请求超时 | 降级处理，返回空结果 |

---

## 9. 实施计划

### 9.1 阶段划分

**阶段 1：基础设施（30 分钟）**
- 添加 Elasticsearch 到 docker-compose.yml
- 添加 Maven 依赖
- 配置 application.yml

**阶段 2：核心实现（1.5 小时）**
- 创建 ChatHistoryDocument
- 实现 ChatHistorySearchRepository
- 实现 ChatHistorySearchService
- 实现 ChatHistoryEsSyncConsumer
- 更新 RabbitMQConfig

**阶段 3：集成（30 分钟）**
- 修改 AiChatService
- 创建 ChatSearchController
- 修改 ChatResponse

**阶段 4：测试（30 分钟）**
- 单元测试
- 集成测试
- Kibana 验证

### 9.2 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| IK 分词器未安装 | 中 | 中 | 使用 ES 内置分词器降级 |
| ES 版本不兼容 | 低 | 高 | 使用 Spring Boot 推荐版本 |
| MQ 消息堆积 | 低 | 中 | 监控队列长度，及时告警 |
| 搜索性能差 | 中 | 中 | 优化索引设置，添加缓存 |

### 9.3 回滚方案

如果出现严重问题，可以快速回滚：
1. 停止 ChatHistoryEsSyncConsumer（注释 @RabbitListener）
2. 移除搜索接口调用
3. 保留 MySQL 数据不变

---

## 10. 总结

### 10.1 核心特性

- ✅ MySQL 主存储，数据可靠性保证
- ✅ 实时同步，延迟 < 1 秒
- ✅ 全文搜索，IK 中文分词
- ✅ 相似推荐，More Like This 查询
- ✅ 双接口，独立搜索 + 自动推荐
- ✅ 增量迁移，只同步新对话

### 10.2 技术栈

- Spring Data Elasticsearch
- RabbitMQ (Spring AMQP)
- Elasticsearch 8.11 + Kibana
- IK 中文分词器

### 10.3 代码量估算

- 新增文件：8 个
- 修改文件：4 个
- 总代码量：约 400 行

### 10.4 实施时间

**总计：约 2.5 小时**

---

## 附录

### A. 相关文档

- [Spring Data Elasticsearch 官方文档](https://docs.spring.io/spring-data/elasticsearch/docs/current/reference/html/)
- [Elasticsearch 官方文档](https://www.elastic.co/guide/en/elasticsearch/reference/current/index.html)
- [IK 分词器](https://github.com/medcl/elasticsearch-analysis-ik)

### B. 后续优化

- 添加搜索结果高亮
- 实现搜索历史记录
- 添加搜索建议（自动补全）
- 实现全量数据迁移脚本
- 优化相似度算法
