---
name: rag-systems
description: RAG architecture, document processing, vector search, evaluation frameworks for gc-qa-rag and medical-qa-rag
---

# RAG Systems Expert

You are an expert in Retrieval-Augmented Generation systems. Use this skill when working with gc-qa-rag, medical-qa-rag, or similar RAG projects.

## Architecture Patterns

### gc-qa-rag (Enterprise-grade)
**Tech Stack**: Python 3.13+, FastAPI, MySQL, Qdrant, DashScope/OpenAI

**Key Features**:
- QA pre-generation: Convert docs → Q&A pairs
- Dynamic chunking: Sentence-level (short) / Two-stage memory-focus (long)
- Hybrid retrieval: Dense + BM25 with RRF ranking
- Reranking: CrossEncoder or LLM-based
- Multi-turn dialogue with context compression

**Modules**:
- `gc-qa-rag-etl`: Document processing, QA generation, vector indexing
- `gc-qa-rag-server`: FastAPI backend with retrieval & generation
- `gc-qa-rag-frontend`: React UI

### medical-qa-rag (Research-focused)
**Tech Stack**: Python 3.9+, FAISS, DashScope, DeepSeek, LangChain

**Key Features**:
- Web scraping for medical documents
- 4 chunking strategies: fixed, structured, semantic, semantic_meta
- Dense/BM25/hybrid retrieval with optional reranking
- Comprehensive evaluation: Recall@K, MRR, Hit@K
- Regression testing with baseline comparison

**Pipeline**: `crawl → clean → chunk → embed → retrieve → rerank → answer → evaluate`

## Common Patterns

### 1. Document Processing
```python
# Cleaning
- Remove footers, contact info, slogans
- Normalize whitespace
- Extract structured sections

# Chunking strategies
- Fixed: 500 chars with 50 overlap
- Structured: By headers/sections
- Semantic: Sentence embeddings + similarity threshold
- Semantic_meta: Semantic + metadata preservation
```

### 2. Hybrid Retrieval
```python
# Dense retrieval (vector similarity)
dense_results = vector_db.search(query_embedding, top_k=20)

# BM25 (keyword matching)
bm25_results = bm25.get_top_n(query, corpus, n=20)

# RRF (Reciprocal Rank Fusion)
final_score = sum(1/(k + rank_i) for each retriever)
```

### 3. Reranking
```python
# CrossEncoder
reranker = CrossEncoder('BAAI/bge-reranker-base')
scores = reranker.predict([(query, doc) for doc in candidates])

# LLM-based
prompt = f"Rate relevance of document to query: {query}\nDocument: {doc}"
score = llm.predict(prompt)
```

### 4. QA Pre-generation (gc-qa-rag)
```python
# Generate Q&A pairs from documents
qa_pairs = llm.generate_qa(
    document=doc,
    num_questions=5,
    difficulty=['easy', 'medium', 'hard']
)
# Index both questions and answers
```

### 5. Evaluation Framework
```python
# Metrics
- Recall@K: % of relevant docs in top K
- MRR (Mean Reciprocal Rank): 1/rank of first relevant doc
- Hit@K: % of queries with ≥1 relevant doc in top K

# Regression testing
baseline = load_baseline_metrics()
current = evaluate_current_system()
assert current.recall_at_5 >= baseline.recall_at_5 * 0.95
```

### 6. Context Compression
```python
# For multi-turn dialogue
compressed_context = llm.compress(
    history=conversation_history,
    max_tokens=2000,
    preserve_recent=True
)
```

## Project-Specific Notes

### gc-qa-rag
- Uses Qdrant for vector storage (production-ready)
- Docker deployment with env vars
- React frontend for Q&A interface
- Two-stage chunking for long documents

### medical-qa-rag
- FAISS for local vector index (research/prototype)
- Medical domain-specific cleaning
- Comprehensive evaluation pipeline
- Baseline comparison for regression testing

## Best Practices
- Always evaluate retrieval quality before generation
- Use hybrid retrieval for better coverage
- Implement reranking for precision
- Monitor evaluation metrics continuously
- Test chunking strategies on domain-specific data
- Use QA pre-generation for FAQ-style systems
- Compress context for multi-turn dialogues
