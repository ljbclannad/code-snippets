# AI对话流程重构设计方案

**日期**: 2026-05-18  
**作者**: Claude Opus 4.7  
**版本**: 1.0  
**实施方式**: 一次性重构

---

## 1. 概述

### 1.1 背景

当前AI对话系统存在以下问题：
- 用户画像每次从MySQL实时查询，性能开销大
- 缺乏会话上下文管理，无法理解多轮对话的连贯性
- Embedding模型使用v2（1536维），需要升级到v3（1024维）
- 记忆检索缺乏快速去重和缓存机制

### 1.2 目标

通过一次性重构实现以下目标：
1. **用户画像Redis缓存**：优先读缓存，支持主动刷新
2. **会话上下文管理**：引入SessionContext，支持意图分析和滑动窗口
3. **Embedding模型升级**：从v2升级到v3，全量迁移历史数据
4. **记忆索引优化**：Redis去重 + 最近记忆缓存
5. **Token预算重构**：调整预算分配，增加会话上下文权重

### 1.3 实施策略

**方案选择**：一次性重构（方案B）

**理由**：
- 架构清晰，没有过渡期的技术债
- 一次性验证完整流程，避免分阶段集成问题
- 上线后即是最终形态

**风险控制**：
- 完整的回滚方案
- 充分的集成测试和压测
- 选择低峰期停机维护

---

## 2. 整体架构

### 2.1 新的对话流程

```
用户消息
  ↓
1. 获取用户画像（Redis Hash: ai:profile:{customerId}）
   - 优先读Redis
   - 不存在则从MySQL构建并回写
   - 问卷/报告解析流程主动刷新缓存
   - TTL: 24小时
  ↓
2. 获取/创建会话上下文（Redis Hash: ai:session:context:{conversationId}）
   - 存在：直接返回
   - 不存在：调用DeepSeek分析意图并创建
   - TTL: 30分钟
  ↓
3. 生成查询向量（千问text-embedding-v3, 1024维）
   - 输入：会话上下文摘要 + 用户当前消息
  ↓
4. 检索相关记忆（Milvus向量搜索）
   - 优先查询Redis缓存（最近20条记忆）
   - 未命中则查询Milvus
   - 相似度阈值：0.7
  ↓
5. 组装Prompt（动态Token预算）
   - 核心画像：600 tokens（必需层）
   - 会话上下文：800 tokens（必需层）
   - 相关记忆：1200 tokens（重要层）
   - 扩展画像：200 tokens（可选层）
   - 用户消息预留：500 tokens
   - 总预算：4000 tokens
  ↓
6. 调用DeepSeek生成回复
  ↓
7. 保存新记忆（三写）
   - MySQL: b_ai_group_memory
   - Milvus: 向量存储（v3 collection）
   - Redis: 去重hash + 最近记忆缓存
  ↓
8. 更新会话上下文（DeepSeek意图分析）
   - 提取实体、意图、摘要
   - 更新SessionContext
   - 刷新TTL（30分钟）
   - 异步执行，不阻塞主流程
```

### 2.2 服务职责变化

| 服务 | 新增职责 | 保持不变 |
|------|---------|---------|
| **user-profile-service** | • RedisProfileService（画像缓存）<br>• 主动刷新接口（/api/profile/refresh）<br>• 画像序列化/反序列化 | • MySQL查询逻辑<br>• RabbitMQ RPC接口<br>• 数据聚合逻辑 |
| **ai-chat-service** | • SessionContextService（上下文管理）<br>• DeepSeekIntentAnalyzer（意图分析）<br>• ContextBudgetManagerV2（新预算管理）<br>• 增强查询构建 | • 对话API<br>• 历史保存<br>• Elasticsearch同步<br>• 推荐功能 |
| **vector-memory-service** | • 千问v3 embedding<br>• RedisMemoryIndexService（去重+缓存）<br>• 生产级迁移脚本 | • Milvus存储<br>• 记忆提取<br>• 相似度检索 |

### 2.3 数据流示例

```
用户: "我最近血糖有点高，想改善饮食"
    ↓
[AI Chat Service]
    ├─ 并行请求:
    │  ├─ UserProfileService: 获取用户画像
    │  │  └─ Redis命中 → 返回缓存画像
    │  └─ SessionContextService: 获取会话上下文
    │     └─ Redis未命中 → DeepSeek分析意图 → 创建新上下文
    │        {
    │          "currentTopic": "血糖管理",
    │          "userIntent": "咨询饮食改善方案",
    │          "mentionedEntities": ["血糖", "饮食"],
    │          "complexity": "simple"
    │        }
    ├─ 构建增强查询: "我最近血糖有点高，想改善饮食 血糖管理 血糖 饮食"
    ├─ VectorMemoryService: 检索相关记忆
    │  ├─ Redis缓存: 返回最近5条记忆
    │  └─ Milvus: 补充查询（topK=5）
    ├─ ContextBudgetManagerV2: 构建系统提示词
    │  ├─ 核心画像: 年龄、性别、当前血糖值（580 tokens）
    │  ├─ 会话上下文: 当前话题、意图、实体（320 tokens）
    │  ├─ 相关记忆: 历史血糖记录、饮食偏好（950 tokens）
    │  └─ 扩展画像: 饮食偏好详情（150 tokens）
    ├─ 调用DeepSeek API生成回复
    ├─ 保存对话历史到MySQL
    ├─ 异步更新会话上下文:
    │  └─ DeepSeek重新分析 → 更新SessionContext → 刷新Redis TTL
    ├─ 异步触发记忆提取:
    │  └─ VectorMemoryService.extractAndSaveMemories()
    │     ├─ DeepSeek提取: "用户血糖偏高，关注饮食改善" (HEALTH, 0.95)
    │     ├─ Redis去重检查: ✓ 通过
    │     ├─ Milvus相似度检查: ✓ 通过
    │     └─ 三写:
    │        ├─ MySQL: INSERT INTO b_ai_group_memory
    │        ├─ Milvus: 向量化（v3, 1024维）并存储
    │        └─ Redis: 添加hash索引 + 最近记忆缓存
    └─ 返回回复 + 相似对话推荐
```

---

## 3. 核心组件设计

详见以下独立文档：
- [会话上下文管理](./2026-05-18-ai-chat-flow-refactor-session-context.md)
- [用户画像缓存](./2026-05-18-ai-chat-flow-refactor-profile-cache.md)
- [向量记忆改造](./2026-05-18-ai-chat-flow-refactor-vector-memory.md)
- [Token预算管理](./2026-05-18-ai-chat-flow-refactor-token-budget.md)
- [生产级增强](./2026-05-18-ai-chat-flow-refactor-production.md)

---

## 4. 数据模型

### 4.1 SessionContext（会话上下文）

```java
@Data
@Builder
public class SessionContext {
    // 会话标识
    private String conversationId;
    private String customerId;
    private String groupId;
    
    // 当前会话状态
    private String currentTopic;              // "运动方案"
    private String conversationPhase;         // "consulting" | "idle"
    
    // 关键信息提取
    private List<String> mentionedEntities;   // ["血糖", "运动", "饭后"]
    private Map<String, String> extractedData; // {"血糖值": "7.2"}
    
    // 最近消息摘要（仅用户消息，50字限制）
    private List<MessageSummary> recentSummaries; // 动态3-5条
    
    // 用户意图
    private String userIntent;                // "咨询运动建议"
    private List<String> pendingQuestions;    // ["饭后多久运动"]
    
    // 复杂度判断（AI输出）
    private String complexity;                // "simple" | "complex"
    
    // 会话元数据
    private Long sessionStartTime;
    private Long lastUpdateTime;
    private Integer messageCount;
}

@Data
public class MessageSummary {
    private String userMessage;               // 用户消息摘要（50字）
    private Long timestamp;
}
```

**Redis存储**：
- Key: `ai:session:context:{conversationId}`
- 类型: String（JSON序列化）
- TTL: 30分钟

### 4.2 用户画像Redis结构

**Redis存储**：
- Key: `ai:profile:{customerId}`
- 类型: Hash
- TTL: 24小时

**Hash字段**：
```
basic:name          → "张三"
basic:age           → 35
basic:gender        → "男"
basic:phone         → "13800138000"
health:bmi          → 24.5
health:bloodPressure → "120/80"
health:bloodSugar   → "5.6"
health:goals        → ["控制血糖", "减重"]
behavior:dietPreference → ["低糖", "高蛋白"]
behavior:exerciseFrequency → "每周3次"
memories            → JSON数组（全局记忆）
```

### 4.3 记忆Redis索引

**去重索引**：
- Key: `ai:memory:hash:{groupId}`
- 类型: Set
- 成员: MD5(content)
- TTL: 7天

**最近记忆缓存**：
- Key: `ai:memory:recent:{groupId}`
- 类型: Sorted Set
- Score: timestamp
- 成员: JSON序列化的MemoryItemDTO
- 大小限制: 20条
- TTL: 24小时

### 4.4 数据库变更

**b_ai_group_memory表新增字段**：
```sql
ALTER TABLE b_ai_group_memory 
ADD COLUMN embedding_version VARCHAR(10) DEFAULT 'v3' COMMENT 'Embedding版本';

CREATE INDEX idx_embedding_version ON b_ai_group_memory(embedding_version);
```

---

## 5. 配置变更

### 5.1 application.yml新增配置

```yaml
# AI服务配置
ai-chat:
  # Token预算配置
  context:
    total-budget: 4000
    core-profile-budget: 600          # 从800降到600
    session-context-budget: 800       # 新增
    relevant-memories-budget: 1200
    extended-profile-budget: 200      # 从400降到200
    user-message-reserve: 500
  
  # 会话上下文配置
  session:
    ttl-minutes: 30
    window-size-simple: 3
    window-size-complex: 5
    summary-max-length: 50
  
  # 记忆配置
  memory:
    recent-cache-size: 20
    recent-cache-ttl-hours: 24
    duplicate-check-enabled: true
    similarity-threshold: 0.7
  
  # 画像配置
  profile:
    cache-ttl-hours: 24
    refresh-on-update: true

# Spring AI配置
spring:
  ai:
    vectorstore:
      milvus:
        embedding-dimension: 1024      # 从1536改为1024
        collection-name: user_memory_vectors_v3  # 新collection
    
    dashscope:
      embedding:
        options:
          model: text-embedding-v3     # 从v2升级到v3
```

---

## 6. API变更

### 6.1 新增接口

**user-profile-service**:

```java
// 主动刷新画像缓存（问卷/报告解析后调用）
POST /api/profile/refresh/{customerId}

// 删除画像缓存
DELETE /api/profile/cache/{customerId}
```

### 6.2 保持不变的接口

- `POST /api/chat` - AI对话接口（入参和返回值不变）
- RabbitMQ RPC接口（user.profile.request、vector.memory.request）

---

## 7. 迁移计划

### 7.1 迁移前准备

**1. 环境准备**
- [ ] 在Milvus中创建新collection: `user_memory_vectors_v3`（1024维）
- [ ] 配置千问API密钥（text-embedding-v3）
- [ ] Redis扩容（预估增加2GB内存）
- [ ] 准备回滚脚本

**2. 数据备份**
- [ ] 备份MySQL数据库（b_ai_group_memory表）
- [ ] 导出Milvus v2 collection数据
- [ ] 备份当前配置文件

**3. 代码准备**
- [ ] 完成所有代码开发和单元测试
- [ ] 完成集成测试
- [ ] 完成压力测试（模拟生产流量）

### 7.2 迁移步骤

**停机窗口**: 预计2-3小时（建议凌晨2:00-5:00）

**步骤1: 停止服务**（5分钟）
```bash
# 停止所有AI服务
systemctl stop ai-chat-service
systemctl stop vector-memory-service
systemctl stop user-profile-service
```

**步骤2: 数据库变更**（5分钟）
```sql
-- 添加embedding_version字段
ALTER TABLE b_ai_group_memory 
ADD COLUMN embedding_version VARCHAR(10) DEFAULT 'v2' COMMENT 'Embedding版本';

CREATE INDEX idx_embedding_version ON b_ai_group_memory(embedding_version);
```

**步骤3: 部署新代码**（10分钟）
```bash
# 部署新版本
cd /opt/ai-services
./deploy.sh v2.0.0
```

**步骤4: 执行Embedding迁移**（1-2小时）
```bash
# 运行迁移脚本
java -jar migration-tool.jar \
  --mode=embedding-migration \
  --batch-size=100 \
  --log-file=/var/log/migration.log
```

**迁移进度监控**：
- 每100条记录输出一次进度
- 记录失败的memoryId
- 支持断点续传

**步骤5: 验证迁移结果**（15分钟）
```bash
# 检查迁移统计
SELECT 
  embedding_version,
  COUNT(*) as count
FROM b_ai_group_memory
GROUP BY embedding_version;

# 预期结果：
# v3: 所有记录
# v2: 0条

# 验证Milvus collection
curl -X GET "http://localhost:19530/v1/collections/user_memory_vectors_v3/stats"
```

**步骤6: 启动服务**（5分钟）
```bash
# 启动服务
systemctl start user-profile-service
systemctl start vector-memory-service
systemctl start ai-chat-service

# 检查健康状态
curl http://localhost:8103/actuator/health
```

**步骤7: 冒烟测试**（10分钟）
```bash
# 测试完整对话流程
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test-user-001",
    "groupId": "test-group-001",
    "conversationId": "test-conv-001",
    "message": "我想了解如何控制血糖"
  }'

# 验证：
# 1. 画像从Redis获取（检查日志）
# 2. 会话上下文创建成功
# 3. 记忆检索正常
# 4. AI回复正常
```

### 7.3 回滚方案

**触发条件**：
- 迁移失败率 > 5%
- 服务启动失败
- 冒烟测试失败
- 生产流量异常

**回滚步骤**：
```bash
# 1. 停止新版本服务
systemctl stop ai-chat-service vector-memory-service user-profile-service

# 2. 回滚代码
cd /opt/ai-services
./rollback.sh v1.0.0

# 3. 恢复配置
cp /backup/application.yml.bak /opt/ai-services/config/application.yml

# 4. 启动旧版本服务
systemctl start user-profile-service vector-memory-service ai-chat-service

# 5. 验证服务正常
curl http://localhost:8103/actuator/health
```

**数据回滚**：
- Milvus v2 collection保留，切换回v2
- MySQL数据不回滚（embedding_version字段兼容）
- Redis数据自动过期，无需处理

---

## 8. 监控和告警

### 8.1 关键指标

**性能指标**：
- `ai.chat.response.time` - 对话响应时间（P50/P95/P99）
- `ai.context.analysis.time` - 意图分析耗时
- `ai.embedding.time` - 向量生成耗时
- `ai.milvus.search.time` - Milvus检索耗时

**缓存指标**：
- `ai.context.cache.hit` - 会话上下文缓存命中数
- `ai.context.cache.miss` - 会话上下文缓存未命中数
- `ai.profile.cache.hit` - 用户画像缓存命中数
- `ai.profile.cache.miss` - 用户画像缓存未命中数

**业务指标**：
- `ai.chat.requests` - 对话请求总数
- `ai.memory.duplicate` - 去重过滤的记忆数
- `ai.session.active` - 活跃会话数

### 8.2 告警规则

```yaml
alerts:
  - name: 对话响应时间过长
    condition: ai.chat.response.time.p95 > 5s
    severity: warning
    
  - name: 画像缓存命中率低
    condition: ai.profile.cache.hit / (ai.profile.cache.hit + ai.profile.cache.miss) < 0.8
    severity: warning
    
  - name: Redis连接失败
    condition: redis.connection.errors > 10
    severity: critical
    
  - name: Milvus检索失败
    condition: milvus.search.errors > 5
    severity: critical
```

---

## 9. 风险评估

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|---------|
| Embedding迁移失败 | 高 | 中 | 断点续传、失败重试、完整回滚方案 |
| Redis内存不足 | 高 | 低 | 提前扩容、设置合理TTL、监控内存使用 |
| DeepSeek API限流 | 中 | 中 | 熔断降级、使用默认上下文 |
| 会话上下文分析超时 | 中 | 低 | 5秒超时、降级到默认上下文 |
| 新旧代码不兼容 | 高 | 低 | 充分测试、保持API接口不变 |

---

## 10. 成功标准

**功能验证**：
- [ ] 用户画像缓存命中率 > 80%
- [ ] 会话上下文创建成功率 > 95%
- [ ] Embedding迁移成功率 > 99%
- [ ] 记忆去重准确率 > 90%

**性能指标**：
- [ ] 对话响应时间P95 < 3秒（当前4秒）
- [ ] 画像获取时间 < 50ms（当前200ms）
- [ ] 记忆检索时间 < 500ms（当前800ms）

**稳定性指标**：
- [ ] 服务可用性 > 99.9%
- [ ] 错误率 < 0.1%
- [ ] 无数据丢失

---

## 11. 后续优化

**短期（1个月内）**：
- 优化意图分析Prompt，提高准确率
- 调优Token预算分配比例
- 完善监控大盘和告警

**中期（3个月内）**：
- 支持多模型Embedding（v3 + v4）
- 会话上下文智能压缩算法
- 记忆检索结果排序优化

**长期（6个月内）**：
- 引入向量数据库集群（高可用）
- 支持多租户隔离
- AI模型微调（领域适配）

---

## 12. 参考文档

- [会话上下文管理详细设计](./2026-05-18-ai-chat-flow-refactor-session-context.md)
- [用户画像缓存详细设计](./2026-05-18-ai-chat-flow-refactor-profile-cache.md)
- [向量记忆改造详细设计](./2026-05-18-ai-chat-flow-refactor-vector-memory.md)
- [Token预算管理详细设计](./2026-05-18-ai-chat-flow-refactor-token-budget.md)
- [生产级增强详细设计](./2026-05-18-ai-chat-flow-refactor-production.md)
