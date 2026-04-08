# 医疗 QA RAG

一个面向医疗政策/医学公开资料的 RAG 原型项目。当前仓库的重点不是完整前后端产品，而是这条知识库链路：

`抓取文档 -> 清洗文本 -> 切分 chunk -> 向量化 -> 检索/重排 -> 基于证据回答 -> 评测`

## 当前能力

- 爬取公开医疗资料，来源包括国家医保局、国家卫健委、PMC OA、LOINC，见 [crawler/MedicalCrawler.py](crawler/MedicalCrawler.py)
- 对原始文档做轻量清洗，去掉页脚、联系方式、口号等噪声，见 [knowledge-base/cleaning.py](knowledge-base/cleaning.py)
- 支持 fixed / structured / semantic / semantic_meta 四种切分策略，见 [knowledge-base/medical_chunk.py](knowledge-base/medical_chunk.py)
- 使用 DashScope embedding 写入本地 FAISS 索引，见 [knowledge-base/medical_embedding.py](knowledge-base/medical_embedding.py)
- 支持 dense、BM25、hybrid 检索，可选 CrossEncoder / LLM rerank、上下文压缩和结构化输出，见 [knowledge-base/medical_model_chat.py](knowledge-base/medical_model_chat.py)
- 提供 Recall@K / MRR / Hit@K 评测与报表汇总，见 [knowledge-base/eval_retrieval.py](knowledge-base/eval_retrieval.py) 和 [knowledge-base/report_eval.py](knowledge-base/report_eval.py)

## 安装

```bash
pip install -r requirements.txt
```

准备环境变量，可参考 [.env.example](.env.example)：

- `DASHSCOPE_API_KEY`：embedding 与 query embedding
- `DEEPSEEK_API_KEY`：问答与 LLM rerank
- 可选：`MEDICAL_DOCS_DIR`、`MEDICAL_QA_EMBEDDING_MODEL`、`MEDICAL_QA_CHAT_MODEL`

## 推荐用法

### 1. 抓取文档

```bash
python rag_pipeline.py crawl --target-count 50 --output-dir medical_docs
```

### 2. 从原始文件构建索引

```bash
python rag_pipeline.py build-index \
  --input medical_docs/medical_documents_xxx.json \
  --strategy semantic_meta \
  --max-chars 500
```

这一步会顺序执行：

- 清洗原始文件
- 生成 `*_chunks.json`
- 构建 `*_faiss_index`

### 3. 基于索引问答

```bash
python rag_pipeline.py answer \
  --index-dir medical_docs/medical_documents_xxx_cleaned_chunks_faiss_index \
  --question "新增药品挂网工作需要在什么时候之前完成？" \
  --retrieval-mode hybrid_rrf
```

## 单脚本入口

如果你想分别执行各阶段，也可以直接用子脚本：

- 爬虫：`python crawler/MedicalCrawler.py --target-count 50`
- 清洗：`python knowledge-base/cleaning.py --input medical_docs --output medical_docs`
- 切分：`python knowledge-base/medical_chunk.py --input medical_docs/foo_cleaned.json --strategy semantic_meta`
- 向量化：`python knowledge-base/medical_embedding.py build --input medical_docs/foo_chunks.json --index-dir medical_docs/foo_faiss_index`
- 简单检索：`python knowledge-base/medical_embedding.py search --index-dir medical_docs/foo_faiss_index --query "医保目录更新时间"`
- 问答：`python knowledge-base/medical_model_chat.py --index-dir medical_docs/foo_faiss_index --question "医保目录更新时间？"`
- 回归评测：`python rag_pipeline.py regression --config eval/regression_config.example.json --output-dir eval/runs/latest`

## 评测

### 评测集格式

支持 JSON 或 JSONL。每条样本至少包含 `query` 或 `question`，以及相关文档标注：

- 方式 A：`relevant_doc_ids`
- 方式 B：`relevant`，元素为 `{"source": "...", "chunk_id": "..."}`
- 方式 C：`relevant_chunk_ids`

示例：

```json
[
  {
    "query": "医保目录更新时间？",
    "relevant_doc_ids": ["医保政策-药品目录调整通知::12"]
  },
  {
    "question": "新增药品挂网工作需要在什么时候之前完成？",
    "relevant": [
      {"source": "医保政策-药品目录调整通知", "chunk_id": "3"}
    ]
  }
]
```

### 运行评测

```bash
python knowledge-base/eval_retrieval.py \
  --index-dir /path/to/faiss_index \
  --data /path/to/eval.json \
  --output /path/to/report.json \
  --retrieval-mode hybrid_rrf \
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

## 回归基线

建议把评测流程固定成下面这个闭环：

1. 在 [eval/datasets/README.md](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/eval/datasets/README.md) 的约束下维护主评测集，例如 `eval/datasets/policy_eval_v1.json`
2. 复制 [eval/regression_config.example.json](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/eval/regression_config.example.json) 并填入真实 `index_dir` 与 `data`
3. 第一次建立基线：

```bash
python rag_pipeline.py regression \
  --config eval/regression_config.example.json \
  --output-dir eval/runs/baseline_candidate \
  --write-baseline eval/baselines/policy_eval_v1_baseline.json
```

4. 后续每次改检索、切分、重排或 embedding 策略后，运行回归比较：

```bash
python rag_pipeline.py regression \
  --config eval/regression_config.example.json \
  --output-dir eval/runs/latest \
  --baseline eval/baselines/policy_eval_v1_baseline.json \
  --fail-on-regression
```

输出内容包括：

- `summary.csv`
- `summary.md`
- `regression_result.json`
- `regression_result.md`
- `reports/*.json`，每个 run 一份原始评测报告

### 基线文件

基线文件样例见 [eval/baselines/example_baseline.json](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/eval/baselines/example_baseline.json)。

建议：

- 每个主评测集对应一份 baseline
- 只有在你确认“当前效果更优且稳定”时，才更新 baseline
- 对 `allowed_drop` 保持谨慎，默认建议不超过 `0.01`

### 汇总报表

```bash
python knowledge-base/report_eval.py \
  --inputs /path/to/report_a.json /path/to/report_b.json \
  --csv-output /path/to/metrics.csv \
  --md-output /path/to/metrics.md
```

## 项目说明

当前仓库没有独立的 `server`、`frontend` 或训练模块目录，README 现在与实际代码保持一致：它是一个偏后端、偏实验性的 RAG 工程，而不是完整业务系统。
