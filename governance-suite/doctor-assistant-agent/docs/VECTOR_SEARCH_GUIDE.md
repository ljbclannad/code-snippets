# 向量检索集成指南

> 历史说明：当前减重管理医生助手的主工具链已不再包含 `search_similar_cases`。
> 本文档保留为此前向量检索实验的参考资料，不代表当前产品主能力范围。

## 概述

本项目集成了 Milvus 向量数据库，用于实现相似病例检索功能。通过将病例文本转换为向量表示，可以快速找到与当前患者症状相似的历史病例，为医生提供参考。

## 架构设计

```
┌─────────────────┐
│  ToolRegistry   │  工具注册中心
│                 │  - search_similar_cases 工具
└────────┬────────┘
         │
         ▼
┌─────────────────────┐
│ VectorSearchService │  向量检索服务
│                     │  - 病例向量化
│                     │  - 相似度搜索
└──────┬──────┬───────┘
       │      │
       ▼      ▼
┌──────────┐ ┌──────────────────┐
│  Milvus  │ │ EmbeddingGenerator│
│  Client  │ │                   │
│          │ │  - Mock 模式      │
│          │ │  - 真实 Embedding │
└──────────┘ └──────────────────┘
```

## 核心组件

### 1. CaseVector（病例向量模型）

```java
@Data
public class CaseVector {
    private String caseId;           // 病例ID
    private String customerId;       // 患者ID
    private String description;      // 病例描述
    private String diagnosis;        // 诊断结果
    private List<String> symptoms;   // 症状列表
    private List<String> chronicDiseases;  // 慢性病列表
    private List<String> medications;      // 处方药物
    private List<Float> embedding;   // 向量表示（768维）
    private Float similarity;        // 相似度分数
}
```

### 2. VectorSearchService（向量检索服务）

**主要功能：**
- `searchSimilarCases(query, topK)` - 搜索相似病例
- `addCase(caseVector)` - 添加单个病例
- `batchAddCases(caseVectors)` - 批量添加病例
- `isAvailable()` - 检查服务是否可用

**病例文本构建：**
```
病例描述: {description}
诊断结果: {diagnosis}
症状: {symptoms}
慢性病: {chronicDiseases}
处方药物: {medications}
```

### 3. MilvusClient（Milvus 客户端）

**Collection Schema：**
- `case_id` (VarChar, 64) - 主键
- `customer_id` (VarChar, 64)
- `description` (VarChar, 2000)
- `diagnosis` (VarChar, 500)
- `embedding` (FloatVector, 768)

**索引配置：**
- 索引类型：IVF_FLAT
- 相似度度量：L2（欧氏距离）
- 参数：nlist=128, nprobe=10

### 4. EmbeddingGenerator（向量生成器）

**两种模式：**

1. **Mock 模式**（默认，用于测试）
   - 使用文本 hashCode 作为种子生成随机向量
   - 相同文本生成相同向量
   - L2 归一化

2. **真实模式**（生产环境）
   - 集成真实的 Embedding 模型
   - 如：OpenAI Embeddings、BGE、M3E 等

### 5. VectorDataInitializer（数据初始化器）

应用启动时自动加载 10 个测试病例到 Milvus。

## 配置说明

### application.yml

```yaml
# Milvus 向量数据库配置
milvus:
  enabled: false          # 是否启用 Milvus（默认关闭）
  host: localhost
  port: 19530
  collection-name: medical_cases
  dimension: 768

# Embedding 配置
embedding:
  use-mock: true          # 使用 Mock Embedding（测试用）

# 向量检索配置
vector:
  search:
    top-k: 5              # 默认返回前5个相似病例
  init-data: false        # 是否在启动时初始化测试数据
```

## 使用方式

### 1. 启动 Milvus（可选）

如果需要使用真实的 Milvus 数据库：

```bash
# 使用 Docker 启动 Milvus
docker run -d --name milvus-standalone \
  -p 19530:19530 \
  -p 9091:9091 \
  milvusdb/milvus:latest
```

然后修改配置：
```yaml
milvus:
  enabled: true
  init-data: true  # 自动加载测试数据
```

### 2. Mock 模式（默认）

不需要启动 Milvus，直接使用 Mock 数据：

```yaml
milvus:
  enabled: false  # 保持关闭
```

ToolRegistry 会自动返回 Mock 病例数据。

### 3. 通过 Agent 工具使用

```json
{
  "goal": "帮我查找类似高血压和超重的病例",
  "customerId": "C001",
  "doctorId": "D001"
}
```

Agent 会自动调用 `search_similar_cases` 工具：

```json
{
  "tool": "search_similar_cases",
  "input": {
    "symptoms": ["高血压", "超重"],
    "limit": 5
  }
}
```

返回结果：
```json
[
  {
    "caseId": "CASE_001",
    "customerId": "C001",
    "description": "35岁女性患者，有高血压病史...",
    "diagnosis": "高血压控制稳定，体重管理成功...",
    "similarity": 0.85
  },
  {
    "caseId": "CASE_005",
    "customerId": "C004",
    "description": "50岁男性患者，高血压和高血脂...",
    "diagnosis": "高血压和高血脂控制中...",
    "similarity": 0.78
  }
]
```

## 测试数据

项目包含 10 个测试病例（见 `sql/vector_test_data.sql`）：

| 病例ID | 患者ID | 主要症状 | 诊断 |
|--------|--------|----------|------|
| CASE_001 | C001 | 高血压, 超重 | 高血压控制稳定，减重成功 |
| CASE_002 | C002 | 高血糖, 肥胖 | 糖尿病控制良好 |
| CASE_003 | C001 | 焦虑, 失眠 | 轻度焦虑 |
| CASE_004 | C003 | 无 | 健康减重计划 |
| CASE_005 | C004 | 高血压, 高血脂, 头晕 | 高血压和高血脂控制中 |
| CASE_006 | C005 | 高血糖, 蛋白尿 | 糖尿病肾病早期 |
| CASE_007 | C006 | 膝关节疼痛, 超重 | 膝关节劳损 |
| CASE_008 | C007 | 高血压, 心悸, 胸闷 | 高血压合并心律不齐 |
| CASE_009 | C008 | 视力下降, 高血糖 | 糖尿病视网膜病变 |
| CASE_010 | C009 | 无 | 减重成功案例 |

## 性能优化

### 1. 索引优化

根据数据规模调整索引参数：

- **小规模（< 10万）**：IVF_FLAT, nlist=128
- **中规模（10万-100万）**：IVF_SQ8, nlist=1024
- **大规模（> 100万）**：HNSW, M=16, efConstruction=200

### 2. 批量操作

使用 `batchAddCases()` 批量插入病例，提高性能。

### 3. 缓存策略

对于热门查询，可以在 Redis 中缓存检索结果。

## 生产环境部署

### 1. 使用真实 Embedding 模型

替换 `EmbeddingGenerator` 中的 Mock 实现：

```java
@Override
public List<Float> generateEmbedding(String text) {
    // 调用 OpenAI Embeddings API
    // 或使用本地 BGE/M3E 模型
    return embeddingModel.embed(text);
}
```

### 2. Milvus 集群部署

生产环境建议使用 Milvus 集群：

```yaml
milvus:
  enabled: true
  host: milvus-cluster.example.com
  port: 19530
```

### 3. 监控指标

- 向量插入速度（QPS）
- 检索延迟（P50, P99）
- 索引构建时间
- 内存使用率

## 故障排查

### 1. Milvus 连接失败

```
错误：Milvus 初始化失败
```

**解决方案：**
- 检查 Milvus 是否启动：`docker ps | grep milvus`
- 检查端口是否开放：`telnet localhost 19530`
- 查看 Milvus 日志：`docker logs milvus-standalone`

### 2. 向量维度不匹配

```
错误：dimension mismatch
```

**解决方案：**
- 确保 `milvus.dimension` 与 Embedding 模型输出维度一致
- 重新创建 Collection

### 3. 检索结果为空

**可能原因：**
- Collection 未加载到内存
- 没有插入数据
- 查询向量异常

**解决方案：**
```bash
# 检查 Collection 状态
curl -X GET "http://localhost:9091/api/v1/collection/medical_cases"

# 检查数据量
curl -X GET "http://localhost:9091/api/v1/collection/medical_cases/count"
```

## 扩展功能

### 1. 多模态检索

支持图像、文本混合检索：
- 病例描述文本向量
- 医学影像图像向量
- 融合检索

### 2. 个性化排序

根据医生偏好调整检索结果排序：
- 历史点击率
- 病例相关性
- 时间新鲜度

### 3. 增量更新

支持病例数据的增量更新：
- 新增病例自动向量化
- 定期重建索引
- 版本管理

## 参考资料

- [Milvus 官方文档](https://milvus.io/docs)
- [向量检索原理](https://zilliz.com/learn/what-is-vector-database)
- [Embedding 模型对比](https://huggingface.co/spaces/mteb/leaderboard)
