# Code Snippets - LLM 与分布式系统学习仓库

## 仓库简介

这是一个全面的代码学习仓库，涵盖了大语言模型（LLM）应用开发、分布式系统、微服务治理、算法实现等多个领域。每个项目都包含完整的代码实现、详细的文档说明和可运行的示例，适合学习和实践。

## 快速导航

### 🤖 LLM 与 AI 应用

| 项目 | 说明 | 技术栈 | 文档 |
|------|------|--------|------|
| [gc-qa-rag](./gc-qa-rag) | 企业级 RAG 系统，QA 预生成技术 | Python, FastAPI, Qdrant | [README](./gc-qa-rag/README.md) |
| [medical-qa-rag](./medical-qa-rag) | 医疗领域 RAG 系统 | Python, FAISS, LangChain | [README](./medical-qa-rag/README.md) |
| [llm-app-dev](./llm-app-dev) | LLM 应用开发实验场 | Python, LangChain, DeepSeek | [README](./llm-app-dev/README.md) |
| [learning-agent](./learning-agent) | 学习型 Agent（TF-IDF 检索） | Python | [README](./learning-agent/README.md) |
| [spring-ai](./spring-ai) | Spring AI 框架演示（含 MCP） | Java, Spring Boot, Spring AI | [README](./spring-ai/README.md) |
| [dify-api-test-java](./dify-api-test-java) | Dify API Java 客户端 | Java | [README](./dify-api-test-java/README.md) |

### 🔧 LLM 基础组件

| 项目 | 说明 | 技术栈 | 文档 |
|------|------|--------|------|
| [from-zero-llm](./from-zero-llm) | 从零实现 Tokenizer、Dataset、Embedding | Python, PyTorch | [README](./from-zero-llm/README.md) |
| [tokenization](./tokenization) | 分词算法集合（BPE、SentencePiece） | Python | [README](./tokenization/README.md) |

### 🏗️ 分布式系统与微服务

| 项目 | 说明 | 技术栈 | 文档 |
|------|------|--------|------|
| [governance-suite](./governance-suite) | 微服务治理全家桶 | Spring Cloud, Nacos, Seata, Sentinel | [README](./governance-suite/README.md) |
| [Snowflake-demo](./Snowflake-demo) | 分布式 ID 生成（雪花算法 + 号段） | Java, Spring Boot, Redis | [README](./Snowflake-demo/README.md) |

### 🚀 算法与数据结构

| 项目 | 说明 | 技术栈 | 文档 |
|------|------|--------|------|
| [DistributedRateLimitingAlgorithm](./DistributedRateLimitingAlgorithm) | 四种限流算法实现 | Java | [README](./DistributedRateLimitingAlgorithm/README.md) |
| [redis](./redis) | Redis 核心功能实现 | Java | [README](./redis/README.md) |

## 技术矩阵

### 按技术栈分类

#### Python 项目
- **LLM 应用**: gc-qa-rag, medical-qa-rag, llm-app-dev, learning-agent
- **基础组件**: from-zero-llm, tokenization

#### Java 项目
- **Spring 生态**: spring-ai, governance-suite, Snowflake-demo
- **算法实现**: DistributedRateLimitingAlgorithm, redis
- **API 集成**: dify-api-test-java

### 按应用场景分类

#### 企业级应用
- **知识库问答**: gc-qa-rag（通用）, medical-qa-rag（医疗）
- **微服务治理**: governance-suite（完整治理方案）
- **分布式 ID**: Snowflake-demo（高性能 ID 生成）

#### 学习与实验
- **LLM 原理**: from-zero-llm（底层实现）, tokenization（分词算法）
- **应用开发**: llm-app-dev（LangChain）, spring-ai（Spring AI）
- **算法实现**: DistributedRateLimitingAlgorithm（限流）, redis（缓存）

#### 工具与集成
- **Agent 系统**: learning-agent（最小实现）
- **API 客户端**: dify-api-test-java（Dify 集成）

## 核心项目详解

### 1. gc-qa-rag - 企业级 RAG 系统 ⭐⭐⭐⭐⭐

**定位**: 生产级 RAG 系统，已在企业环境验证

**核心创新**:
- **高级 QA 预生成技术**: 将文档转化为高质量问答对
- **两阶段记忆-聚焦机制**: 解决长文档信息覆盖问题
- **多维度知识增强**: 自动生成摘要、扩充答案、同义问法

**技术亮点**:
- ETL 知识库构建流程
- 混合检索 + RRF 排序
- Docker 一键部署
- 完整的评测体系

**适用场景**: 企业知识库、客服系统、文档问答

**快速开始**:
```bash
cd gc-qa-rag
docker compose -f sources/gc-qa-rag-etl/deploy/docker-compose.dockerhub.yml up -d
docker compose -f sources/gc-qa-rag-server/deploy/docker-compose.dockerhub.yml up -d
```

---

### 2. governance-suite - 微服务治理全家桶 ⭐⭐⭐⭐⭐

**定位**: 分布式系统学习的完整示例

**核心能力**:
- **服务治理**: Nacos 注册发现、Gateway 路由
- **分布式事务**: Seata AT 模式
- **最终一致性**: RabbitMQ + Outbox 模式
- **流量控制**: Sentinel 限流熔断
- **数据分片**: ShardingSphere-JDBC

**学习路径**:
1. 服务注册与发现 → 2. 分布式事务 → 3. 最终一致性 → 4. 幂等与防重 → 5. 限流降级

**实验手册**:
- Lab 01: Nacos 服务发现
- Lab 02: Seata 全局事务回滚
- Lab 03: Outbox 重试与最终一致性
- Lab 04: 幂等与秒杀防重

**快速开始**:
```bash
cd governance-suite
docker compose up -d
mvn -T 1C clean package -DskipTests
mvn -q -pl gateway spring-boot:run
```

---

### 3. from-zero-llm - LLM 基础组件实现 ⭐⭐⭐⭐

**定位**: 从零理解 LLM 的底层原理

**核心模块**:
- **Tokenizer**: 简易分词器、BPE 算法
- **Dataset**: GPT 风格滑动窗口数据集
- **Embedding**: Token Embedding + Position Embedding

**学习价值**:
- 理解 BPE 如何平衡词汇表大小和表达能力
- 掌握滑动窗口数据集的生成原理
- 了解词嵌入的组合方式

**快速开始**:
```bash
cd from-zero-llm
python tokenizer/BpeTest.py
python dataset/GPTDatesetTest.py
python embedding/GPTEmbeddingTest.py
```

---

### 4. spring-ai - Spring AI 框架演示 ⭐⭐⭐⭐

**定位**: Spring AI 的全面实践

**核心功能**:
- 基础对话、提示词工程
- 结构化输出、工具调用
- 对话记忆（JDBC 持久化）
- MCP 协议集成（Server + Client）

**技术亮点**:
- 完整的 MCP 天气查询示例
- 多种工具定义方式（注解式、函数式）
- H2 内存数据库存储会话

**快速开始**:
```bash
cd spring-ai/demo
export DEEPSEEK_API_KEY="your-api-key"
mvn spring-boot:run
curl "http://localhost:8081/ai/tool/weather?city=杭州"
```

---

### 5. Snowflake-demo - 分布式 ID 生成 ⭐⭐⭐⭐

**定位**: 高性能分布式主键生成方案

**核心特性**:
- **双策略支持**: 雪花算法 + 号段模式
- **运行时切换**: 无需重启即可切换策略
- **灰度发布**: 按比例分流到不同策略
- **健康检查**: WorkerId 租约、Segment 分配状态

**技术亮点**:
- Redis 管理 WorkerId 租约
- MySQL 号段预分配
- Prometheus 指标监控

**快速开始**:
```bash
cd Snowflake-demo
docker compose up -d
mvn spring-boot:run
curl -X POST "http://localhost:8080/api/orders" -H "Content-Type: application/json" -d '{"userId": 1001, "amount": 12.34}'
```

---

## 快速开始指南

### 环境准备

#### Python 项目
```bash
# 创建虚拟环境
python -m venv venv
source venv/bin/activate  # Linux/Mac
# 或
venv\Scripts\activate  # Windows

# 安装依赖
pip install -r requirements.txt
```

#### Java 项目
```bash
# 确保已安装
java -version  # JDK 17+
mvn -version   # Maven 3.8+

# 编译项目
mvn clean package -DskipTests
```

### 常用命令

#### Docker 相关
```bash
# 启动基础设施
docker compose up -d

# 查看日志
docker compose logs -f

# 停止服务
docker compose down

# 清理数据
docker compose down -v
```

#### Maven 相关
```bash
# 编译
mvn clean package -DskipTests

# 运行
mvn spring-boot:run

# 多模块编译
mvn -T 1C clean package -DskipTests

# 运行指定模块
mvn -q -pl module-name spring-boot:run
```

## 学习路径建议

### 路径 1: LLM 应用开发（初学者）

1. **基础概念** → `from-zero-llm`
   - 理解 Tokenizer、Dataset、Embedding
   - 运行 BPE 算法，观察分词效果

2. **API 调用** → `llm-app-dev/api`
   - 学习 OpenAI SDK 基础用法
   - 理解 temperature、max_tokens 等参数

3. **提示词工程** → `llm-app-dev/langchainTest`
   - 掌握提示词模板
   - 实践 Few-Shot 学习

4. **RAG 系统** → `llm-app-dev/ragTest`
   - 理解向量检索原理
   - 构建简单的问答系统

5. **生产级 RAG** → `gc-qa-rag`
   - 学习 QA 预生成技术
   - 部署完整的 RAG 系统

### 路径 2: 分布式系统（进阶）

1. **限流算法** → `DistributedRateLimitingAlgorithm`
   - 理解四种限流算法
   - 对比不同算法的适用场景

2. **缓存实现** → `redis`
   - 学习跳表数据结构
   - 实现逻辑过期缓存

3. **分布式 ID** → `Snowflake-demo`
   - 理解雪花算法原理
   - 掌握号段模式

4. **微服务治理** → `governance-suite`
   - 服务注册与发现
   - 分布式事务
   - 最终一致性
   - 限流熔断

### 路径 3: Spring 生态（Java 开发者）

1. **Spring AI 基础** → `spring-ai`
   - 基础对话和提示词
   - 工具调用
   - 对话记忆

2. **Spring Cloud** → `governance-suite`
   - Gateway 路由
   - OpenFeign 调用
   - Sentinel 限流

3. **Spring Boot 实践** → `Snowflake-demo`
   - 健康检查
   - Prometheus 监控
   - 配置管理

## 技术栈总览

### 后端框架
- **Spring Boot**: 3.4.0
- **Spring Cloud**: 2023.0.x
- **Spring AI**: 1.1.2
- **LangChain**: 最新版

### 数据库
- **MySQL**: 8.0
- **Redis**: 7.0
- **H2**: 内存数据库

### 向量数据库
- **FAISS**: Facebook AI Similarity Search
- **Qdrant**: 高性能向量数据库
- **Chroma**: 轻量级向量数据库

### 中间件
- **Nacos**: 服务注册与配置中心
- **Seata**: 分布式事务
- **RabbitMQ**: 消息队列
- **Sentinel**: 流量控制

### 大模型
- **DeepSeek**: 主要使用的对话模型
- **阿里云百炼**: 嵌入模型（text-embedding-v4）

### 开发工具
- **Docker**: 容器化部署
- **Maven**: Java 项目管理
- **PDM**: Python 依赖管理

## 项目特色

### 1. 完整性
- 每个项目都包含完整的代码实现
- 提供详细的 README 文档
- 包含可运行的示例和测试

### 2. 实用性
- 多个项目已在生产环境验证
- 提供 Docker 一键部署
- 包含性能优化和最佳实践

### 3. 学习友好
- 从基础到进阶的完整路径
- 详细的代码注释
- 丰富的实验手册

### 4. 技术前沿
- 涵盖最新的 LLM 技术
- Spring AI、MCP 等新框架
- 企业级 RAG 系统实践

## 常见问题

### Q: 如何选择合适的项目开始学习？

A: 根据你的背景选择：
- **LLM 初学者**: from-zero-llm → llm-app-dev → gc-qa-rag
- **Java 开发者**: spring-ai → Snowflake-demo → governance-suite
- **Python 开发者**: tokenization → llm-app-dev → medical-qa-rag
- **分布式系统**: DistributedRateLimitingAlgorithm → redis → governance-suite

### Q: 项目之间有依赖关系吗？

A: 大部分项目是独立的，但建议按以下顺序学习：
- LLM 基础: from-zero-llm → tokenization → llm-app-dev
- RAG 系统: llm-app-dev → medical-qa-rag → gc-qa-rag
- 分布式: DistributedRateLimitingAlgorithm → Snowflake-demo → governance-suite

### Q: 需要什么样的硬件配置？

A: 
- **最低配置**: 8GB RAM, 4 核 CPU
- **推荐配置**: 16GB RAM, 8 核 CPU
- **向量数据库**: 建议 SSD 存储
- **Docker**: 至少分配 4GB 内存

### Q: 如何获取 API Key？

A: 
- **DeepSeek**: https://platform.deepseek.com/
- **阿里云百炼**: https://bailian.console.aliyun.com/
- **OpenAI**: https://platform.openai.com/

### Q: 遇到问题如何解决？

A: 
1. 查看项目的 README 文档
2. 检查环境配置（API Key、依赖版本）
3. 查看 Docker 日志：`docker compose logs -f`
4. 查看应用日志

## 贡献指南

欢迎贡献代码、文档或提出建议！

### 贡献方式
1. Fork 本仓库
2. 创建特性分支
3. 提交变更
4. 发起 Pull Request

### 代码规范
- Java: 遵循 Google Java Style Guide
- Python: 遵循 PEP 8
- 提交信息: 使用清晰的中文描述

## 更新日志

### 2026-04-14
- ✅ 完成所有项目的 README 文档
- ✅ 添加技术矩阵和学习路径
- ✅ 更新主 README 导航

### 2026-04-13
- ✅ 新增 Spring AI MCP 天气演示
- ✅ 完善 governance-suite 文档

### 2026-04-12
- ✅ 优化 Snowflake-demo 健康检查
- ✅ 添加 Prometheus 指标

## 许可证

本仓库中的项目主要用于学习和演示目的。具体许可证请查看各项目的 LICENSE 文件。

## 联系方式

- **作者**: LeJinBo
- **GitHub**: https://github.com/LeJinBo

---

**⭐ 如果这个仓库对你有帮助，欢迎 Star！**
