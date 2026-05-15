# MySQL + Elasticsearch 双写方案实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 ai-chat-service 实现 MySQL + Elasticsearch 双写方案，提供对话历史的全文搜索和相似对话推荐功能

**Architecture:** MySQL 作为主存储保证数据可靠性，通过 RabbitMQ 异步同步到 Elasticsearch 搜索层，提供独立搜索接口和自动推荐功能

**Tech Stack:** Spring Data Elasticsearch, RabbitMQ (Spring AMQP), Elasticsearch 8.11, Kibana, IK 中文分词器

---

## 文件结构

### 新增文件

**数据模型**:
- `ai-chat-service/src/main/java/com/lx/governance/aichat/document/ChatHistoryDocument.java` - ES 文档实体
- `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ChatHistorySyncMessage.java` - MQ 同步消息 DTO

**数据访问层**:
- `ai-chat-service/src/main/java/com/lx/governance/aichat/repository/ChatHistorySearchRepository.java` - ES Repository

**业务逻辑层**:
- `ai-chat-service/src/main/java/com/lx/governance/aichat/service/ChatHistorySearchService.java` - 搜索服务
- `ai-chat-service/src/main/java/com/lx/governance/aichat/consumer/ChatHistoryEsSyncConsumer.java` - MQ 消费者

**控制器**:
- `ai-chat-service/src/main/java/com/lx/governance/aichat/controller/ChatSearchController.java` - 搜索接口

**配置文件**:
- `ai-chat-service/src/main/resources/elasticsearch/chat-history-settings.json` - ES 索引设置

**测试**:
- `ai-chat-service/src/test/java/com/lx/governance/aichat/service/ChatHistorySearchServiceTest.java` - 搜索服务测试

### 修改文件

- `docker-compose.yml` - 添加 Elasticsearch 和 Kibana
- `ai-chat-service/pom.xml` - 添加 Spring Data Elasticsearch 依赖
- `ai-chat-service/src/main/resources/application.yml` - 添加 ES 和 RabbitMQ 配置
- `ai-chat-service/src/main/java/com/lx/governance/aichat/config/RabbitMQConfig.java` - 添加同步队列配置
- `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java` - 添加 ES 同步逻辑
- `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ChatResponse.java` - 添加相似对话字段

---

## Task 1: 配置 Docker Compose

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: 添加 Elasticsearch 服务**

在 `docker-compose.yml` 的 `services` 部分添加：

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
```

- [ ] **Step 2: 添加 ES 数据卷**

在 `docker-compose.yml` 的 `volumes` 部分添加：

```yaml
    gov_es_data:
```

- [ ] **Step 3: 启动 Elasticsearch 和 Kibana**

Run:
```bash
cd /Users/lejinbo/LLM/code-snippets/governance-suite
docker-compose up -d elasticsearch kibana
```

Expected: 容器启动成功，等待健康检查通过

- [ ] **Step 4: 验证 Elasticsearch 启动**

Run:
```bash
curl http://localhost:9200
```

Expected: 返回 ES 集群信息 JSON

- [ ] **Step 5: 安装 IK 分词器**

Run:
```bash
docker exec -it gov-elasticsearch /bin/bash -c "./bin/elasticsearch-plugin install https://github.com/medcl/elasticsearch-analysis-ik/releases/download/v8.11.0/elasticsearch-analysis-ik-8.11.0.zip"
docker restart gov-elasticsearch
```

Expected: 插件安装成功，容器重启

- [ ] **Step 6: 验证 IK 分词器**

Run:
```bash
sleep 30  # 等待 ES 重启完成
curl -X POST "http://localhost:9200/_analyze" -H 'Content-Type: application/json' -d'
{
  "analyzer": "ik_max_word",
  "text": "我最近失眠怎么办"
}'
```

Expected: 返回分词结果

- [ ] **Step 7: 提交配置**

Run:
```bash
git add docker-compose.yml
git commit -m "config: add Elasticsearch and Kibana to docker-compose

添加 Elasticsearch 8.11 和 Kibana 服务
- 单节点模式，禁用安全认证
- 配置健康检查
- 安装 IK 中文分词器

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 2: 添加 Maven 依赖

**Files:**
- Modify: `ai-chat-service/pom.xml`

- [ ] **Step 1: 添加 Spring Data Elasticsearch 依赖**

在 `ai-chat-service/pom.xml` 的 `<dependencies>` 部分，在 `spring-ai-openai-spring-boot-starter` 之后添加：

```xml
        <!-- Elasticsearch -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
        </dependency>
```

- [ ] **Step 2: 验证依赖解析**

Run:
```bash
cd ai-chat-service
mvn dependency:tree | grep elasticsearch
```

Expected: 显示 spring-boot-starter-data-elasticsearch 及其传递依赖

- [ ] **Step 3: 提交依赖配置**

Run:
```bash
git add ai-chat-service/pom.xml
git commit -m "build: add Spring Data Elasticsearch dependency

添加 spring-boot-starter-data-elasticsearch 依赖
用于实现对话历史的全文搜索和相似推荐

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 3: 配置 Application

**Files:**
- Modify: `ai-chat-service/src/main/resources/application.yml`

- [ ] **Step 1: 添加 Elasticsearch 配置**

在 `application.yml` 的 `spring` 部分添加：

```yaml
  elasticsearch:
    uris: http://localhost:9200
    connection-timeout: 5s
    socket-timeout: 30s
```

- [ ] **Step 2: 添加 RabbitMQ 重试配置**

在 `application.yml` 的 `spring.rabbitmq` 部分添加：

```yaml
    listener:
      simple:
        retry:
          enabled: true
          initial-interval: 3000
          max-attempts: 3
          multiplier: 2
```

- [ ] **Step 3: 添加对话历史同步配置**

在 `application.yml` 根级别添加：

```yaml
# 对话历史同步配置
chat:
  history:
    sync:
      queue: chat.history.sync.queue
      exchange: chat.history.sync.exchange
      routing-key: chat.history.sync
```

- [ ] **Step 4: 添加健康检查配置**

在 `application.yml` 的 `management` 部分更新：

```yaml
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

- [ ] **Step 5: 提交配置**

Run:
```bash
git add ai-chat-service/src/main/resources/application.yml
git commit -m "config: add Elasticsearch and chat history sync configuration

添加配置：
- Elasticsearch 连接配置
- RabbitMQ 重试机制
- 对话历史同步队列配置
- Elasticsearch 健康检查

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 4: 创建 ES 索引设置文件

**Files:**
- Create: `ai-chat-service/src/main/resources/elasticsearch/chat-history-settings.json`

- [ ] **Step 1: 创建 elasticsearch 目录**

Run:
```bash
mkdir -p ai-chat-service/src/main/resources/elasticsearch
```

Expected: 目录创建成功

- [ ] **Step 2: 创建索引设置文件**

创建文件 `ai-chat-service/src/main/resources/elasticsearch/chat-history-settings.json`：

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

- [ ] **Step 3: 提交索引设置**

Run:
```bash
git add ai-chat-service/src/main/resources/elasticsearch/
git commit -m "config: add Elasticsearch index settings for chat history

添加 IK 分词器配置：
- ik_max_word: 索引时使用，最细粒度分词
- ik_smart: 搜索时使用，智能分词

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 5: 创建 ChatHistoryDocument

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/document/ChatHistoryDocument.java`

- [ ] **Step 1: 创建 document 包**

Run:
```bash
mkdir -p ai-chat-service/src/main/java/com/lx/governance/aichat/document
```

Expected: 目录创建成功

- [ ] **Step 2: 创建 ChatHistoryDocument 类**

创建文件 `ai-chat-service/src/main/java/com/lx/governance/aichat/document/ChatHistoryDocument.java`：

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

/**
 * 对话历史 ES 文档
 */
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

    /**
     * 用户消息 - 使用 IK 分词器进行全文搜索
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String userMessage;

    /**
     * AI 回复 - 使用 IK 分词器进行全文搜索
     */
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

- [ ] **Step 3: 验证编译**

Run:
```bash
cd ai-chat-service
mvn compile
```

Expected: 编译成功，无错误

- [ ] **Step 4: 提交文档实体**

Run:
```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/document/
git commit -m "feat: add ChatHistoryDocument for Elasticsearch

创建对话历史 ES 文档实体：
- 使用 @Document 注解定义索引名称
- 使用 IK 分词器进行中文全文搜索
- 包含所有对话历史字段

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 6: 创建 ChatHistorySyncMessage

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ChatHistorySyncMessage.java`

- [ ] **Step 1: 创建 ChatHistorySyncMessage 类**

创建文件 `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ChatHistorySyncMessage.java`：

```java
package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对话历史同步消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistorySyncMessage implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
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

- [ ] **Step 2: 验证编译**

Run:
```bash
cd ai-chat-service
mvn compile
```

Expected: 编译成功，无错误

- [ ] **Step 3: 提交同步消息 DTO**

Run:
```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ChatHistorySyncMessage.java
git commit -m "feat: add ChatHistorySyncMessage DTO

创建对话历史同步消息 DTO：
- 用于 RabbitMQ 消息传递
- 包含所有需要同步到 ES 的字段
- 实现 Serializable 接口

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---
## Task 7: 创建 ChatHistorySearchRepository

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/repository/ChatHistorySearchRepository.java`

- [ ] **Step 1: 创建 repository 包（如果不存在）**

Run:
```bash
mkdir -p ai-chat-service/src/main/java/com/lx/governance/aichat/repository
```

Expected: 目录创建成功或已存在

- [ ] **Step 2: 创建 ChatHistorySearchRepository 接口**

创建文件 `ai-chat-service/src/main/java/com/lx/governance/aichat/repository/ChatHistorySearchRepository.java`：

```java
package com.lx.governance.aichat.repository;

import com.lx.governance.aichat.document.ChatHistoryDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 对话历史搜索 Repository
 */
@Repository
public interface ChatHistorySearchRepository extends ElasticsearchRepository<ChatHistoryDocument, String> {

    /**
     * 根据客户ID和关键词搜索（用户消息或AI回复中包含关键词）
     */
    Page<ChatHistoryDocument> findByCustomerIdAndUserMessageContainingOrAiResponseContaining(
            String customerId, String keyword1, String keyword2, Pageable pageable);

    /**
     * 根据客户ID查询最近的对话
     */
    List<ChatHistoryDocument> findTop10ByCustomerIdOrderByCreateTimeDesc(String customerId);
}
```

- [ ] **Step 3: 验证编译**

Run:
```bash
cd ai-chat-service
mvn compile
```

Expected: 编译成功，无错误

- [ ] **Step 4: 提交 Repository**

Run:
```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/repository/ChatHistorySearchRepository.java
git commit -m "feat: add ChatHistorySearchRepository

创建对话历史搜索 Repository：
- 继承 ElasticsearchRepository
- 提供全文搜索方法
- 提供最近对话查询方法

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 8: 更新 RabbitMQConfig

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/config/RabbitMQConfig.java`

- [ ] **Step 1: 添加对话历史同步队列常量**

在 `RabbitMQConfig` 类中添加常量（在现有常量之后）：

```java
    // 对话历史同步队列
    public static final String CHAT_HISTORY_SYNC_QUEUE = "chat.history.sync.queue";
    public static final String CHAT_HISTORY_SYNC_EXCHANGE = "chat.history.sync.exchange";
    public static final String CHAT_HISTORY_SYNC_ROUTING_KEY = "chat.history.sync";
```

- [ ] **Step 2: 添加队列 Bean**

在 `RabbitMQConfig` 类中添加方法：

```java
    /**
     * 对话历史同步队列
     */
    @Bean
    public Queue chatHistorySyncQueue() {
        return new Queue(CHAT_HISTORY_SYNC_QUEUE, true);
    }
```

- [ ] **Step 3: 添加交换机 Bean**

```java
    /**
     * 对话历史同步交换机
     */
    @Bean
    public DirectExchange chatHistorySyncExchange() {
        return new DirectExchange(CHAT_HISTORY_SYNC_EXCHANGE, true, false);
    }
```

- [ ] **Step 4: 添加绑定 Bean**

```java
    /**
     * 对话历史同步绑定
     */
    @Bean
    public Binding chatHistorySyncBinding() {
        return BindingBuilder
                .bind(chatHistorySyncQueue())
                .to(chatHistorySyncExchange())
                .with(CHAT_HISTORY_SYNC_ROUTING_KEY);
    }
```

- [ ] **Step 5: 验证编译**

Run:
```bash
cd ai-chat-service
mvn compile
```

Expected: 编译成功，无错误

- [ ] **Step 6: 提交 RabbitMQ 配置**

Run:
```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/config/RabbitMQConfig.java
git commit -m "config: add chat history sync queue to RabbitMQ

添加对话历史同步队列配置：
- 定义队列、交换机、路由键常量
- 创建持久化队列
- 配置 DirectExchange 和绑定关系

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 9: 创建 ChatHistorySearchService

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/ChatHistorySearchService.java`

- [ ] **Step 1: 创建 ChatHistorySearchService 类**

创建文件 `ai-chat-service/src/main/java/com/lx/governance/aichat/service/ChatHistorySearchService.java`：

```java
package com.lx.governance.aichat.service;

import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.repository.ChatHistorySearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话历史搜索服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistorySearchService {

    private final ChatHistorySearchRepository searchRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * 全文搜索对话历史
     */
    public Page<ChatHistoryDocument> searchHistory(String customerId, String keyword, int page, int size) {
        log.info("搜索对话历史 - customerId: {}, keyword: {}", customerId, keyword);
        
        PageRequest pageRequest = PageRequest.of(page, size);
        return searchRepository.findByCustomerIdAndUserMessageContainingOrAiResponseContaining(
                customerId, keyword, keyword, pageRequest);
    }

    /**
     * 查找相似对话（基于文本相似度）
     */
    public List<ChatHistoryDocument> findSimilarConversations(String customerId, String message, int limit) {
        log.info("查找相似对话 - customerId: {}, message: {}", customerId, message);

        try {
            // 使用简单的文本匹配查询（More Like This 需要更复杂的配置）
            // 这里先实现基础版本，提取关键词进行搜索
            Criteria criteria = new Criteria("customerId").is(customerId)
                    .and(new Criteria("userMessage").contains(message)
                            .or("aiResponse").contains(message));
            
            Query query = new CriteriaQuery(criteria);
            query.setMaxResults(limit);

            SearchHits<ChatHistoryDocument> searchHits = elasticsearchOperations.search(
                    query, ChatHistoryDocument.class);

            return searchHits.stream()
                    .map(SearchHit::getContent)
                    .limit(limit)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("查找相似对话失败 - customerId: {}", customerId, e);
            return List.of();
        }
    }

    /**
     * 保存对话到 ES
     */
    public void saveToEs(ChatHistoryDocument document) {
        try {
            searchRepository.save(document);
            log.debug("对话已同步到 ES - id: {}", document.getId());
        } catch (Exception e) {
            log.error("保存对话到 ES 失败 - id: {}", document.getId(), e);
            throw e;
        }
    }
}
```

- [ ] **Step 2: 验证编译**

Run:
```bash
cd ai-chat-service
mvn compile
```

Expected: 编译成功，无错误

- [ ] **Step 3: 提交搜索服务**

Run:
```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/service/ChatHistorySearchService.java
git commit -m "feat: add ChatHistorySearchService

创建对话历史搜索服务：
- 实现全文搜索功能
- 实现相似对话查找（基础版本）
- 提供保存到 ES 的方法

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 10: 创建 ChatHistoryEsSyncConsumer

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/consumer/ChatHistoryEsSyncConsumer.java`

- [ ] **Step 1: 创建 consumer 包**

Run:
```bash
mkdir -p ai-chat-service/src/main/java/com/lx/governance/aichat/consumer
```

Expected: 目录创建成功

- [ ] **Step 2: 创建 ChatHistoryEsSyncConsumer 类**

创建文件 `ai-chat-service/src/main/java/com/lx/governance/aichat/consumer/ChatHistoryEsSyncConsumer.java`：

```java
package com.lx.governance.aichat.consumer;

import com.lx.governance.aichat.config.RabbitMQConfig;
import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.dto.ChatHistorySyncMessage;
import com.lx.governance.aichat.service.ChatHistorySearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 对话历史 ES 同步消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatHistoryEsSyncConsumer {

    private final ChatHistorySearchService searchService;

    @RabbitListener(queues = "#{T(com.lx.governance.aichat.config.RabbitMQConfig).CHAT_HISTORY_SYNC_QUEUE}")
    public void syncToEs(ChatHistorySyncMessage message) {
        log.info("收到对话历史同步消息 - id: {}, customerId: {}", message.getId(), message.getCustomerId());

        try {
            // 转换为 ES 文档
            ChatHistoryDocument document = ChatHistoryDocument.builder()
                    .id(String.valueOf(message.getId()))
                    .customerId(message.getCustomerId())
                    .conversationId(message.getConversationId())
                    .userMessage(message.getUserMessage())
                    .aiResponse(message.getAiResponse())
                    .promptTokens(message.getPromptTokens())
                    .completionTokens(message.getCompletionTokens())
                    .totalTokens(message.getTotalTokens())
                    .createTime(message.getCreateTime())
                    .build();

            // 保存到 ES
            searchService.saveToEs(document);
            
            log.info("对话历史同步成功 - id: {}", message.getId());

        } catch (Exception e) {
            log.error("对话历史同步失败 - id: {}", message.getId(), e);
            throw e;  // 触发重试
        }
    }
}
```

- [ ] **Step 3: 验证编译**

Run:
```bash
cd ai-chat-service
mvn compile
```

Expected: 编译成功，无错误

- [ ] **Step 4: 提交 MQ 消费者**

Run:
```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/consumer/
git commit -m "feat: add ChatHistoryEsSyncConsumer

创建对话历史 ES 同步消费者：
- 监听 chat.history.sync.queue
- 转换 MQ 消息为 ES 文档
- 调用搜索服务保存到 ES
- 失败时抛出异常触发重试

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 11: 修改 AiChatService 添加 ES 同步

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java`

- [ ] **Step 1: 添加 RabbitTemplate 依赖**

在 `AiChatService` 类的依赖注入部分添加：

```java
    private final RabbitTemplate rabbitTemplate;
```

- [ ] **Step 2: 在 saveConversationHistory 方法末尾添加 ES 同步调用**

在 `saveConversationHistory()` 方法的 `chatHistoryMapper.insert(history);` 之后添加：

```java
            // 异步同步到 ES
            sendToEsSync(history);
```

- [ ] **Step 3: 添加 sendToEsSync 方法**

在 `AiChatService` 类中添加新方法：

```java
    /**
     * 发送对话历史到 ES 同步队列
     */
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

- [ ] **Step 4: 添加必要的 import**

确保文件顶部有以下 import：

```java
import com.lx.governance.aichat.config.RabbitMQConfig;
import com.lx.governance.aichat.dto.ChatHistorySyncMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
```

- [ ] **Step 5: 验证编译**

Run:
```bash
cd ai-chat-service
mvn compile
```

Expected: 编译成功，无错误

- [ ] **Step 6: 提交 AiChatService 修改**

Run:
```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java
git commit -m "feat: integrate ES sync in AiChatService

集成 ES 同步到对话保存流程：
- 添加 RabbitTemplate 依赖
- 保存到 MySQL 后发送 MQ 消息
- 异步同步到 ES，不影响主流程

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 12: 创建 ChatSearchController

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/controller/ChatSearchController.java`

- [ ] **Step 1: 创建 ChatSearchController 类**

创建文件 `ai-chat-service/src/main/java/com/lx/governance/aichat/controller/ChatSearchController.java`：

```java
package com.lx.governance.aichat.controller;

import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.service.ChatHistorySearchService;
import com.lx.governance.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 对话历史搜索控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/search")
@RequiredArgsConstructor
public class ChatSearchController {

    private final ChatHistorySearchService searchService;

    /**
     * 全文搜索对话历史
     */
    @GetMapping
    public Result<Page<ChatHistoryDocument>> searchHistory(
            @RequestParam String customerId,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        log.info("搜索对话历史 - customerId: {}, keyword: {}", customerId, keyword);
        
        try {
            Page<ChatHistoryDocument> result = searchService.searchHistory(customerId, keyword, page, size);
            return Result.success(result);
        } catch (Exception e) {
            log.error("搜索对话历史失败 - customerId: {}, keyword: {}", customerId, keyword, e);
            return Result.error("搜索失败: " + e.getMessage());
        }
    }

    /**
     * 查找相似对话
     */
    @GetMapping("/similar")
    public Result<List<ChatHistoryDocument>> findSimilar(
            @RequestParam String customerId,
            @RequestParam String message,
            @RequestParam(defaultValue = "5") int limit) {
        
        log.info("查找相似对话 - customerId: {}, message: {}", customerId, message);
        
        try {
            List<ChatHistoryDocument> result = searchService.findSimilarConversations(customerId, message, limit);
            return Result.success(result);
        } catch (Exception e) {
            log.error("查找相似对话失败 - customerId: {}, message: {}", customerId, message, e);
            return Result.error("查找失败: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 2: 验证编译**

Run:
```bash
cd ai-chat-service
mvn compile
```

Expected: 编译成功，无错误

- [ ] **Step 3: 提交搜索控制器**

Run:
```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/controller/ChatSearchController.java
git commit -m "feat: add ChatSearchController

创建对话历史搜索控制器：
- GET /api/chat/search - 全文搜索接口
- GET /api/chat/search/similar - 相似对话推荐接口
- 统一异常处理和日志记录

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 13: 修改 ChatResponse 添加相似对话字段

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ChatResponse.java`

- [ ] **Step 1: 添加 similarConversations 字段**

在 `ChatResponse` 类中添加字段：

```java
    private List<ChatHistoryDocument> similarConversations;
```

- [ ] **Step 2: 添加 withSimilarConversations 方法**

在 `ChatResponse` 类中添加方法：

```java
    /**
     * 添加相似对话推荐
     */
    public ChatResponse withSimilarConversations(List<ChatHistoryDocument> similar) {
        this.similarConversations = similar;
        return this;
    }
```

- [ ] **Step 3: 添加必要的 import**

确保文件顶部有以下 import：

```java
import com.lx.governance.aichat.document.ChatHistoryDocument;
import java.util.List;
```

- [ ] **Step 4: 验证编译**

Run:
```bash
cd ai-chat-service
mvn compile
```

Expected: 编译成功，无错误

- [ ] **Step 5: 提交 ChatResponse 修改**

Run:
```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ChatResponse.java
git commit -m "feat: add similar conversations field to ChatResponse

添加相似对话推荐字段：
- 新增 similarConversations 字段
- 提供 withSimilarConversations 方法
- 支持自动推荐功能

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 14: 在 AiChatService 中添加自动推荐

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java`

- [ ] **Step 1: 添加 ChatHistorySearchService 依赖**

在 `AiChatService` 类的依赖注入部分添加：

```java
    private final ChatHistorySearchService chatHistorySearchService;
```

- [ ] **Step 2: 在 handleChatRequest 方法中添加相似对话查找**

在 `handleChatRequest()` 方法中，`return ChatResponse.success(...)` 之前添加：

```java
            // 4. 查找相似对话（可选，不影响主流程）
            List<ChatHistoryDocument> similarConversations = List.of();
            try {
                similarConversations = chatHistorySearchService.findSimilarConversations(
                        request.getCustomerId(), 
                        request.getMessage(), 
                        3);
            } catch (Exception e) {
                log.warn("查找相似对话失败 - customerId: {}", request.getCustomerId(), e);
            }
```

- [ ] **Step 3: 修改返回语句添加相似对话**

将原来的：

```java
            return ChatResponse.success(difyResponse.getAnswer(), difyResponse.getConversationId());
```

修改为：

```java
            return ChatResponse.success(difyResponse.getAnswer(), difyResponse.getConversationId())
                    .withSimilarConversations(similarConversations);
```

- [ ] **Step 4: 添加必要的 import**

确保文件顶部有以下 import：

```java
import com.lx.governance.aichat.document.ChatHistoryDocument;
```

- [ ] **Step 5: 验证编译**

Run:
```bash
cd ai-chat-service
mvn compile
```

Expected: 编译成功，无错误

- [ ] **Step 6: 提交自动推荐功能**

Run:
```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java
git commit -m "feat: add automatic similar conversation recommendation

添加自动推荐功能：
- 在对话响应中自动附带相似对话
- 失败时降级处理，不影响主流程
- 默认推荐 3 条相似对话

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 15: 创建搜索服务测试

**Files:**
- Create: `ai-chat-service/src/test/java/com/lx/governance/aichat/service/ChatHistorySearchServiceTest.java`

- [ ] **Step 1: 创建测试类**

创建文件 `ai-chat-service/src/test/java/com/lx/governance/aichat/service/ChatHistorySearchServiceTest.java`：

```java
package com.lx.governance.aichat.service;

import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.repository.ChatHistorySearchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对话历史搜索服务测试
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatHistorySearchServiceTest {

    @Autowired
    private ChatHistorySearchService searchService;

    @Autowired
    private ChatHistorySearchRepository searchRepository;

    @Test
    void testSaveAndSearch() {
        // 1. 保存测试数据
        ChatHistoryDocument doc = ChatHistoryDocument.builder()
                .id("test-" + System.currentTimeMillis())
                .customerId("customer-test")
                .conversationId("conv-test")
                .userMessage("我最近失眠怎么办")
                .aiResponse("失眠可能是压力过大导致的，建议您保持规律作息")
                .promptTokens(100)
                .completionTokens(50)
                .totalTokens(150)
                .createTime(LocalDateTime.now())
                .build();

        searchService.saveToEs(doc);

        // 等待 ES 索引刷新
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 2. 全文搜索
        Page<ChatHistoryDocument> results = searchService.searchHistory(
                "customer-test", "失眠", 0, 10);
        
        assertNotNull(results);
        assertTrue(results.getTotalElements() > 0);
        assertTrue(results.getContent().get(0).getUserMessage().contains("失眠"));

        // 3. 相似对话搜索
        List<ChatHistoryDocument> similar = searchService.findSimilarConversations(
                "customer-test", "睡不着觉", 5);
        
        assertNotNull(similar);
        
        // 清理测试数据
        searchRepository.deleteById(doc.getId());
    }

    @Test
    void testSearchWithNoResults() {
        Page<ChatHistoryDocument> results = searchService.searchHistory(
                "non-existent-customer", "不存在的关键词", 0, 10);
        
        assertNotNull(results);
        assertEquals(0, results.getTotalElements());
    }
}
```

- [ ] **Step 2: 运行测试（需要 ES 运行）**

Run:
```bash
cd ai-chat-service
mvn test -Dtest=ChatHistorySearchServiceTest
```

Expected: 测试通过（如果 ES 未运行，测试会失败，这是正常的）

- [ ] **Step 3: 提交测试代码**

Run:
```bash
git add ai-chat-service/src/test/java/com/lx/governance/aichat/service/ChatHistorySearchServiceTest.java
git commit -m "test: add ChatHistorySearchService tests

添加搜索服务测试：
- 测试保存和搜索功能
- 测试相似对话查找
- 测试空结果场景

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Expected: 提交成功

---

## Task 16: 集成测试和验证

**Files:**
- None (manual testing)

- [ ] **Step 1: 启动所有服务**

Run:
```bash
cd /Users/lejinbo/LLM/code-snippets/governance-suite
docker-compose up -d
```

Expected: 所有容器启动成功

- [ ] **Step 2: 验证 Elasticsearch 健康状态**

Run:
```bash
curl http://localhost:9200/_cluster/health
```

Expected: 返回 status: "green" 或 "yellow"

- [ ] **Step 3: 启动 ai-chat-service**

Run:
```bash
cd ai-chat-service
mvn spring-boot:run
```

Expected: 服务启动成功，日志显示连接到 ES 和 RabbitMQ

- [ ] **Step 4: 检查健康检查**

Run:
```bash
curl http://localhost:8080/actuator/health
```

Expected: 返回 status: "UP"，elasticsearch 状态为 UP

- [ ] **Step 5: 发送测试对话**

Run:
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test-user-001",
    "groupId": "test-group",
    "conversationId": "test-conv-001",
    "message": "我最近总是失眠，该怎么办？"
  }'
```

Expected: 返回 AI 回复，包含 conversationId

- [ ] **Step 6: 等待 ES 同步**

Run:
```bash
sleep 2
```

Expected: 等待 2 秒让 MQ 消息被消费

- [ ] **Step 7: 验证 ES 中的数据**

Run:
```bash
curl "http://localhost:9200/chat_history/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "query": {
    "match": {
      "customerId": "test-user-001"
    }
  }
}'
```

Expected: 返回刚才保存的对话记录

- [ ] **Step 8: 测试全文搜索接口**

Run:
```bash
curl "http://localhost:8080/api/chat/search?customerId=test-user-001&keyword=失眠"
```

Expected: 返回包含"失眠"关键词的对话历史

- [ ] **Step 9: 测试相似对话接口**

Run:
```bash
curl "http://localhost:8080/api/chat/search/similar?customerId=test-user-001&message=睡不着觉&limit=3"
```

Expected: 返回相似的对话记录

- [ ] **Step 10: 在 Kibana 中验证**

访问 http://localhost:5601，在 Dev Tools 中执行：

```
GET /chat_history/_search
{
  "query": {
    "match": {
      "userMessage": "失眠"
    }
  }
}
```

Expected: 返回搜索结果，验证索引结构正确

- [ ] **Step 11: 验证自动推荐功能**

再次发送对话请求，检查响应中是否包含 `similarConversations` 字段：

Run:
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test-user-001",
    "groupId": "test-group",
    "conversationId": "test-conv-002",
    "message": "我睡不着觉"
  }'
```

Expected: 响应中包含 similarConversations 数组

- [ ] **Step 12: 检查 RabbitMQ 队列**

访问 http://localhost:15672（用户名/密码：guest/guest），检查 `chat.history.sync.queue` 队列状态

Expected: 队列存在，消息已被消费（Ready: 0）

---

## 实施总结

完成以上所有任务后，你已经成功实现了 MySQL + Elasticsearch 双写方案：

**已实现功能**：
- ✅ Elasticsearch 和 Kibana 容器化部署
- ✅ IK 中文分词器安装
- ✅ Spring Data Elasticsearch 集成
- ✅ 对话历史 ES 文档模型
- ✅ RabbitMQ 异步同步机制
- ✅ 全文搜索接口
- ✅ 相似对话推荐接口
- ✅ 自动推荐功能
- ✅ 单元测试和集成测试

**技术栈**：
- Spring Data Elasticsearch
- RabbitMQ (Spring AMQP)
- Elasticsearch 8.11 + Kibana
- IK 中文分词器

**代码统计**：
- 新增文件：8 个
- 修改文件：4 个
- 总代码量：约 400 行
- 提交次数：16 次

**后续优化建议**：
- 添加搜索结果高亮
- 实现 More Like This 高级相似度算法
- 添加搜索历史记录
- 实现全量数据迁移脚本
- 添加搜索性能监控

---

## 故障排查

如果遇到问题，参考以下排查步骤：

**问题 1：ES 连接失败**
- 检查 ES 容器是否启动：`docker ps | grep elasticsearch`
- 检查 ES 健康状态：`curl http://localhost:9200/_cluster/health`
- 检查应用配置：`spring.elasticsearch.uris`

**问题 2：IK 分词器不工作**
- 验证插件安装：`docker exec -it gov-elasticsearch ./bin/elasticsearch-plugin list`
- 测试分词器：`curl -X POST "http://localhost:9200/_analyze" -H 'Content-Type: application/json' -d'{"analyzer": "ik_max_word", "text": "测试"}'`

**问题 3：MQ 消息未消费**
- 检查队列是否创建：访问 RabbitMQ 管理界面
- 检查消费者是否启动：查看应用日志
- 检查消息格式：确保 DTO 可序列化

**问题 4：搜索无结果**
- 检查索引是否创建：`curl http://localhost:9200/_cat/indices`
- 检查文档数量：`curl http://localhost:9200/chat_history/_count`
- 等待索引刷新：ES 有近实时延迟（约 1 秒）

**问题 5：编译错误**
- 清理并重新编译：`mvn clean compile`
- 检查依赖版本：`mvn dependency:tree`
- 更新 IDE 索引

---
