# AI 记忆功能设计文档 - 第二部分：数据库设计

## 文档信息

- **创建日期**: 2026-05-13
- **项目**: governance-suite
- **功能**: AI 聊天用户记忆功能
- **文档版本**: 1.0
- **依赖文档**: 第一部分 - 整体架构

## 一、MySQL 表设计

### 1.1 群组记忆表 (b_ai_group_memory)

**用途**：存储从对话中提取的群组维度记忆

**表结构**：

```sql
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
```

**字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键，自增 |
| customer_id | VARCHAR(64) | 用户ID，关联用户 |
| group_id | VARCHAR(64) | 群组ID，标识记忆所属群组 |
| memory_type | VARCHAR(32) | 记忆类型枚举值 |
| memory_key | VARCHAR(128) | 记忆关键词，用于快速检索 |
| memory_content | TEXT | 记忆的完整内容 |
| embedding_id | VARCHAR(128) | Milvus 中的向量ID，用于关联 |
| confidence_score | DECIMAL(3,2) | AI 提取的置信度，0-1 之间 |
| source_message | TEXT | 原始对话消息，用于追溯 |
| extracted_at | BIGINT | 提取时间戳（毫秒） |
| is_deleted | TINYINT | 逻辑删除标记 |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

**索引说明**：

- `idx_customer_group`：联合索引，用于查询某用户在某群组的所有记忆
- `idx_memory_type`：用于按类型筛选记忆
- `idx_embedding_id`：用于通过向量ID反查记忆详情

**记忆类型枚举**：

```java
public enum MemoryType {
    SYMPTOM("症状描述"),
    ALLERGY("过敏史"),
    MEDICATION("用药信息"),
    DIET("饮食偏好/禁忌"),
    PREFERENCE("个人偏好"),
    OTHER("其他");
    
    private final String description;
}
```

---

### 1.2 用户全局记忆表 (b_ai_user_memory)

**用途**：存储用户全局维度的重要记忆（跨群组）

**表结构**：

```sql
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
```

**字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键，自增 |
| customer_id | VARCHAR(64) | 用户ID |
| memory_type | VARCHAR(32) | 全局记忆类型枚举值 |
| memory_key | VARCHAR(128) | 记忆关键词 |
| memory_content | TEXT | 记忆内容 |
| embedding_id | VARCHAR(128) | Milvus 中的向量ID |
| confidence_score | DECIMAL(3,2) | 置信度分数 |
| source | VARCHAR(64) | 记忆来源 |
| verified | TINYINT | 是否已人工验证 |
| verified_by | VARCHAR(64) | 验证人ID（医生/管理员） |
| verified_at | DATETIME | 验证时间 |
| is_deleted | TINYINT | 逻辑删除标记 |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

**索引说明**：

- `uk_customer_type_key`：唯一索引，防止同一用户的同类型同关键词记忆重复
- `idx_embedding_id`：用于通过向量ID反查
- `idx_verified`：用于筛选已验证/未验证的记忆

**全局记忆类型枚举**：

```java
public enum GlobalMemoryType {
    ALLERGY("过敏史"),
    CHRONIC_DISEASE("慢性病"),
    MEDICATION("长期用药"),
    DIET_RESTRICTION("饮食禁忌"),
    OTHER("其他");
    
    private final String description;
}
```

**记忆来源枚举**：

```java
public enum MemorySource {
    CHAT("对话提取"),
    MANUAL("人工录入"),
    IMPORT("数据导入");
    
    private final String description;
}
```

---

### 1.3 聊天历史表 (b_ai_chat_history)

**用途**：存储 AI 聊天历史记录（可选，用于审计和分析）

**表结构**：

```sql
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
```

**字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键，自增 |
| customer_id | VARCHAR(64) | 用户ID |
| group_id | VARCHAR(64) | 群组ID |
| conversation_id | VARCHAR(128) | Dify 会话ID，用于关联会话 |
| user_message | TEXT | 用户发送的消息 |
| ai_response | TEXT | AI 的回复内容 |
| role | VARCHAR(32) | 角色类型 |
| trace_id | VARCHAR(64) | 链路追踪ID |
| response_time | INT | 响应时间（毫秒） |
| is_deleted | TINYINT | 逻辑删除标记 |
| create_time | DATETIME | 创建时间 |

**索引说明**：

- `idx_customer_group`：用于查询用户在某群组的聊天历史
- `idx_conversation`：用于查询某个会话的所有消息
- `idx_trace_id`：用于链路追踪
- `idx_create_time`：用于按时间范围查询

---

## 二、Milvus Collection 设计

### 2.1 群组记忆 Collection (ai_group_memories)

**用途**：存储群组记忆的向量表示，用于语义检索

**Collection 配置**：

```json
{
  "collection_name": "ai_group_memories",
  "description": "群组记忆向量存储",
  "enable_dynamic_field": false,
  "fields": [
    {
      "name": "id",
      "type": "VARCHAR",
      "max_length": 128,
      "is_primary": true,
      "description": "记忆唯一ID，与MySQL的embedding_id对应"
    },
    {
      "name": "customer_id",
      "type": "VARCHAR",
      "max_length": 64,
      "description": "用户ID"
    },
    {
      "name": "group_id",
      "type": "VARCHAR",
      "max_length": 64,
      "description": "群组ID"
    },
    {
      "name": "memory_type",
      "type": "VARCHAR",
      "max_length": 32,
      "description": "记忆类型"
    },
    {
      "name": "memory_content",
      "type": "VARCHAR",
      "max_length": 2000,
      "description": "记忆内容（用于返回结果）"
    },
    {
      "name": "embedding",
      "type": "FLOAT_VECTOR",
      "dim": 1024,
      "description": "通义千问生成的1024维向量"
    },
    {
      "name": "timestamp",
      "type": "INT64",
      "description": "提取时间戳"
    }
  ]
}
```

**索引配置**：

```json
{
  "index_type": "IVF_FLAT",
  "metric_type": "COSINE",
  "params": {
    "nlist": 128
  }
}
```

**索引说明**：

- **IVF_FLAT**：倒排文件索引，适合中等规模数据（10万-100万向量）
- **COSINE**：余弦相似度，适合文本向量检索
- **nlist=128**：聚类中心数量，影响检索速度和精度的平衡

**检索参数**：

```json
{
  "metric_type": "COSINE",
  "params": {
    "nprobe": 10
  },
  "limit": 5,
  "expr": "customer_id == 'user123' && group_id == 'group456'"
}
```

---

### 2.2 用户全局记忆 Collection (ai_user_memories)

**用途**：存储用户全局记忆的向量表示

**Collection 配置**：

```json
{
  "collection_name": "ai_user_memories",
  "description": "用户全局记忆向量存储",
  "enable_dynamic_field": false,
  "fields": [
    {
      "name": "id",
      "type": "VARCHAR",
      "max_length": 128,
      "is_primary": true,
      "description": "记忆唯一ID"
    },
    {
      "name": "customer_id",
      "type": "VARCHAR",
      "max_length": 64,
      "description": "用户ID"
    },
    {
      "name": "memory_type",
      "type": "VARCHAR",
      "max_length": 32,
      "description": "记忆类型"
    },
    {
      "name": "memory_content",
      "type": "VARCHAR",
      "max_length": 2000,
      "description": "记忆内容"
    },
    {
      "name": "embedding",
      "type": "FLOAT_VECTOR",
      "dim": 1024,
      "description": "1024维向量"
    },
    {
      "name": "verified",
      "type": "BOOL",
      "description": "是否已验证"
    },
    {
      "name": "timestamp",
      "type": "INT64",
      "description": "创建时间戳"
    }
  ]
}
```

**索引配置**：与群组记忆相同

---

## 三、数据初始化脚本

### 3.1 MySQL 初始化脚本

**文件位置**：`sql/05-ai-memory-schema.sql`

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

---

## 四、数据迁移策略

### 4.1 现有数据迁移

如果系统中已有用户健康数据，需要迁移到全局记忆表：

```sql
-- 迁移过敏史数据（示例）
INSERT INTO b_ai_user_memory 
  (customer_id, memory_type, memory_key, memory_content, source, verified)
SELECT 
  customer_id,
  'ALLERGY' as memory_type,
  allergy_name as memory_key,
  CONCAT('对', allergy_name, '过敏') as memory_content,
  'IMPORT' as source,
  1 as verified
FROM t_customer_allergy
WHERE is_deleted = 0;

-- 迁移慢性病数据（示例）
INSERT INTO b_ai_user_memory 
  (customer_id, memory_type, memory_key, memory_content, source, verified)
SELECT 
  customer_id,
  'CHRONIC_DISEASE' as memory_type,
  disease_name as memory_key,
  disease_description as memory_content,
  'IMPORT' as source,
  1 as verified
FROM t_customer_disease
WHERE is_deleted = 0;
```

### 4.2 向量数据生成

迁移后需要为这些记忆生成向量并保存到 Milvus：

```java
@Service
public class MemoryMigrationService {
    
    @Autowired
    private AiUserMemoryMapper userMemoryMapper;
    
    @Autowired
    private VectorStoreService vectorStoreService;
    
    /**
     * 为现有记忆生成向量
     */
    public void generateEmbeddingsForExistingMemories() {
        // 查询所有未生成向量的记忆
        List<AiUserMemoryEntity> memories = userMemoryMapper.selectWithoutEmbedding();
        
        for (AiUserMemoryEntity memory : memories) {
            try {
                // 保存到向量库（会自动生成 embedding）
                vectorStoreService.saveUserMemory(memory);
                
                // 更新 MySQL 中的 embedding_id
                userMemoryMapper.updateEmbeddingId(memory.getId(), memory.getEmbeddingId());
                
            } catch (Exception e) {
                log.error("生成向量失败: memoryId={}", memory.getId(), e);
            }
        }
    }
}
```

---

## 五、数据维护策略

### 5.1 数据清理

**定期清理策略**：

```sql
-- 清理6个月前的低置信度记忆
UPDATE b_ai_group_memory 
SET is_deleted = 1 
WHERE confidence_score < 0.7 
  AND create_time < DATE_SUB(NOW(), INTERVAL 6 MONTH);

-- 清理1年前的聊天历史
UPDATE b_ai_chat_history 
SET is_deleted = 1 
WHERE create_time < DATE_SUB(NOW(), INTERVAL 1 YEAR);
```

### 5.2 数据归档

对于已删除的数据，定期归档到历史表：

```sql
-- 创建归档表
CREATE TABLE b_ai_group_memory_archive LIKE b_ai_group_memory;

-- 归档数据
INSERT INTO b_ai_group_memory_archive 
SELECT * FROM b_ai_group_memory 
WHERE is_deleted = 1 
  AND update_time < DATE_SUB(NOW(), INTERVAL 3 MONTH);

-- 删除已归档数据
DELETE FROM b_ai_group_memory 
WHERE is_deleted = 1 
  AND update_time < DATE_SUB(NOW(), INTERVAL 3 MONTH);
```

---

## 六、数据一致性保证

### 6.1 MySQL 与 Milvus 双写

**策略**：先写 MySQL，再写 Milvus

```java
@Transactional
public void saveMemory(AiGroupMemoryEntity memory) {
    // 1. 保存到 MySQL
    memoryMapper.insert(memory);
    
    try {
        // 2. 保存到 Milvus
        String embeddingId = vectorStoreService.saveMemory(memory);
        
        // 3. 更新 MySQL 中的 embedding_id
        memory.setEmbeddingId(embeddingId);
        memoryMapper.updateEmbeddingId(memory.getId(), embeddingId);
        
    } catch (Exception e) {
        log.error("保存到 Milvus 失败，记录到补偿队列", e);
        // 记录到补偿队列，后续重试
        compensationService.recordFailedEmbedding(memory.getId());
    }
}
```

### 6.2 数据补偿机制

定时任务检查并补偿失败的向量写入：

```java
@Scheduled(cron = "0 */10 * * * ?")  // 每10分钟执行一次
public void compensateFailedEmbeddings() {
    List<Long> failedIds = compensationService.getFailedEmbeddingIds();
    
    for (Long id : failedIds) {
        try {
            AiGroupMemoryEntity memory = memoryMapper.selectById(id);
            String embeddingId = vectorStoreService.saveMemory(memory);
            memoryMapper.updateEmbeddingId(id, embeddingId);
            compensationService.markAsSuccess(id);
        } catch (Exception e) {
            log.error("补偿失败: id={}", id, e);
        }
    }
}
```

---

## 七、总结

本文档详细设计了 AI 记忆功能的数据库结构，包括：

1. **MySQL 表设计**：群组记忆表、用户全局记忆表、聊天历史表
2. **Milvus Collection 设计**：向量存储结构和索引配置
3. **数据初始化脚本**：完整的建表和测试数据脚本
4. **数据迁移策略**：现有数据迁移和向量生成方案
5. **数据维护策略**：清理、归档和一致性保证

下一步将在第三部分详细设计核心组件的实现。
