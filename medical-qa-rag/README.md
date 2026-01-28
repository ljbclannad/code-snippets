# 医疗QA RAG

## 简介

医疗QA RAG 是一个基于 RAG 的医疗领域问答系统，旨在通过自然语言处理和机器学习技术，实现医疗领域知识的高效检索和问答。

## 技术栈

- Python
- PyTorch
- Transformers
- LangChain
- RAG

## 项目结构

- crawler：医疗文档爬虫
- knowledge-base：医疗知识库
- model：医疗模型
- server：医疗问答服务
- frontend：医疗问答前端

## 快速开始

1. 安装依赖
2. 运行爬虫
3. 运行知识库清洗
4. 运行知识库向量化
5. 运行知识库构建
6. 运行问答服务
7. 运行问答前端

## 使用方法

1. 访问爬虫管理后台
2. 上传医疗文档
3. 等待文档处理完成
4. 发布文档到知识库
5. 访问问答服务
6. 访问问答前端
7. 开始提问

## 评测 v1（Recall@K / MRR / 命中率）

### 评测集格式
支持 JSON 或 JSONL，每条样本至少包含 query/question 和相关文档标注：

- 方式 A：使用 `relevant_doc_ids`（推荐，稳定）
- 方式 B：使用 `relevant`（source + chunk_id）
- 方式 C：仅使用 `relevant_chunk_ids`（可能存在冲突，谨慎）

示例：
```json
[
  {
    "query": "医保目录更新时间？",
    "relevant_doc_ids": ["医保政策-药品目录调整通知::12", "医保政策-药品目录调整通知::15"]
  },
  {
    "question": "新增药品挂网工作需要在什么时候之前完成？",
    "relevant": [
      {"source": "医保政策-药品目录调整通知", "chunk_id": "3"}
    ]
  }
]
```

### 评测脚本
```bash
python knowledge-base/eval_retrieval.py \
  --index-dir /path/to/faiss_index \
  --data /path/to/eval.json \
  --output /path/to/report.json \
  --retrieval-mode dense \
  --ks 1,3,5,10
```

可选开启重排：
```bash
python knowledge-base/eval_retrieval.py \
  --index-dir /path/to/faiss_index \
  --data /path/to/eval.json \
  --output /path/to/report.json \
  --retrieval-mode dense \
  --rerank cross_encoder \
  --cross-encoder-model BAAI/bge-reranker-base
```

### 报表脚本（汇总多次评测）
```bash
python knowledge-base/report_eval.py \
  --inputs /path/to/report_a.json /path/to/report_b.json \
  --csv-output /path/to/metrics.csv \
  --md-output /path/to/metrics.md
```

## 注意事项
