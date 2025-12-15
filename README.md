# code-snippets 项目索引（按目录）

[TOC]
- [code-snippets 项目索引（按目录）](#code-snippets-项目索引按目录)
    - [DistributedRateLimitingAlgorithm](#distributedratelimitingalgorithm)
    - [from-zero-llm](#from-zero-llm)
    - [gc-qa-rag](#gc-qa-rag)
    - [llm-app-dev](#llm-app-dev)
    - [tokenization](#tokenization)


本文件用于记录仓库根目录下**每个已存在的项目文件夹**的用途与入口，方便快速定位代码。

---

## DistributedRateLimitingAlgorithm

- **定位**：常见限流算法（计数器、滑动窗口、令牌桶、漏桶）的 Java 版实现与简单演示。
- **关键文件**
  - **计数器**：`DistributedRateLimitingAlgorithm/RateLimiter.java`
  - **滑动窗口（计数版）**：`DistributedRateLimitingAlgorithm/SlidingWindowCounter.java`
  - **滑动窗口（时间戳版）**：`DistributedRateLimitingAlgorithm/SlidingTimeWindowTimeStamp.java`
  - **令牌桶**：`DistributedRateLimitingAlgorithm/TokenBucket.java`
  - **漏桶**：`DistributedRateLimitingAlgorithm/LeakyBucket.java`
- **如何运行（示例）**
  - 在该目录下编译并运行某个类（每个文件都带 `main`）：

    ```bash
    cd DistributedRateLimitingAlgorithm
    javac *.java
    java RateLimiter
    ```

---

## from-zero-llm

- **定位**：从零实现/理解 LLM 相关基础组件的练习脚本（偏 tokenizer/BPE 入门）。
- **关键文件**
    - **tokenizer**
        - **简易 tokenizer（V1/V2）**：`from-zero-llm/SimpleTokenizer.py`
        - **Tokenizer 测试**：`from-zero-llm/SimpleTokenizerV1Test.py`、`from-zero-llm/SimpleTokenizerV2Test.py`
        - **tiktoken（GPT-2 编码）示例**：`from-zero-llm/BpeTest.py`
    - **dataset**
        - **简易dataset数据集算法**：`from-zero-llm/dataset/GPTDateset.py`
        - **GPTDatesetTest测试脚本**：`from-zero-llm/dataset/GPTDatesetTest.py`
    - **embedding**
        - **embedding测试脚本**：`from-zero-llm/embedding/embeddingTest.py`
        - **GPTEmbeddingTest测试脚本**：`from-zero-llm/embedding/GPTEmbeddingTest.py`
    - **示例语料**：`from-zero-llm/the-verdict.txt`

---

## gc-qa-rag

- **定位**：企业级 RAG 系统（包含 ETL 知识库构建、后端服务、前端界面），配套完整文档与 Docker 部署方案。
- **入口/文档**
  - **项目总览**：`gc-qa-rag/README.md`
  - **快速开始**：`gc-qa-rag/quickstart.md`
  - **文档站源码（mkdocs）**：`gc-qa-rag/docs/`、`gc-qa-rag/mkdocs.yml`
- **模块目录（核心代码）**
  - **ETL（知识库构建）**：`gc-qa-rag/sources/gc-qa-rag-etl/`
  - **Server（RAG 后端）**：`gc-qa-rag/sources/gc-qa-rag-server/`
  - **Frontend（主前端）**：`gc-qa-rag/sources/gc-qa-rag-frontend/`
- **如何运行**
  - 优先参考：`gc-qa-rag/README.md`（Docker 一键部署）与 `gc-qa-rag/quickstart.md`（本地开发）
  - 常见开发端口（以文档为准）：
    - ETL：`http://0.0.0.0:8001`
    - Server：`http://0.0.0.0:8000`
    - Frontend：开发模式由 Vite 启动（见 `quickstart.md`）

---

## llm-app-dev

- **定位**：LLM 应用开发实验场（OpenAI/DeepSeek API、LangChain、RAG/向量检索、记忆等）。
- **子目录说明**
  - **api**：`llm-app-dev/api/test.py`（基础对话调用示例）
  - **langchainTest**：Prompt 模板、输出解析等（如 `promptTemplate.py`）
  - **memoryTest**：对话记忆（如 `MessageHistory.py` 使用 `RunnableWithMessageHistory`）
  - **ragTest**：向量库/检索/Agent 实验（如 `semanticSearchTest.py`、`ragAgentTest.py`）
  - **video-script-generator**：视频脚本生成相关（`requirements.txt`、`utils.py`）
- **运行前常见配置**
  - 多数脚本依赖环境变量（按脚本实际使用为准）：
    - `DEEPSEEK_API_KEY`（用于 `base_url="https://api.deepseek.com"` 的 OpenAI 兼容调用）
    - DashScope 相关 Key（用于 `DashScopeEmbeddings(model="text-embedding-v4")`）
- **如何运行（示例）**
  - 直接运行某个脚本（请先装依赖并配置 API Key）：

    ```bash
    cd llm-app-dev/ragTest
    python semanticSearchTest.py
    ```

---

## tokenization

- **定位**：分词/编码实验集合（BPE、Regex、GPT-2 encoder、Transformers tokenizer、SentencePiece 等）。
- **关键文件/目录**
  - **BPE（字节级合并）实现示例**：`tokenization/bpe.py`
  - **GPT-2 encoder 相关**：`tokenization/encoder.json`、`tokenization/vocab.bpe`、`tokenization/encoderGpt2.py`
  - **DeepSeek R1 tokenizer（本地加载测试）**：`tokenization/deepseekr1/tokenizerTest.py`、`tokenization/deepseekr1/tokenizer.json`、`tokenization/deepseekr1/tokenizer_config.json`
  - **SentencePiece 示例**：`tokenization/sentencepiece/sentencepieceTest.py`
  - **示意图**：`tokenization/tokenization.png`


