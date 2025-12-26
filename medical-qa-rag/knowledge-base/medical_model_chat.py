
import os
import re
from typing import Any, Dict, List, Tuple, Optional

from langchain_openai.chat_models import ChatOpenAI
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.retrievers import BM25Retriever
from langchain_community.vectorstores import FAISS
from langchain_core.documents import Document
from langchain_core.messages import SystemMessage, HumanMessage


# ----------------------------
# 1) 初始化：LLM（DeepSeek）
# ----------------------------
chat_model = ChatOpenAI(
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    model="deepseek-chat",
    base_url="https://api.deepseek.com",
)

# ----------------------------
# 2) 加载 FAISS 索引
#    注意：FAISS.load_local 需要传入 embeddings（用于把 query 向量化）
# ----------------------------
def load_faiss(index_dir: str) -> FAISS:
    if not os.getenv("DASHSCOPE_API_KEY"):
        raise EnvironmentError("请先设置环境变量 DASHSCOPE_API_KEY（用于 query embedding）")
    embeddings = DashScopeEmbeddings(model="text-embedding-v4")
    vs = FAISS.load_local(
        index_dir,
        embeddings,
        allow_dangerous_deserialization=True,  # 本地可信索引可用
    )
    return vs


# ----------------------------
# 3) 检索：拿到 docs + scores
#    LangChain 的 FAISS: similarity_search_with_score 返回 (doc, score)
#    score 通常是“距离”，越小越相似（不同版本封装可能略有差异）
# ----------------------------
def retrieve(
    vs: FAISS,
    query: str,
    k: int = 5,
) -> List[Tuple[Document, float]]:
    return vs.similarity_search_with_score(query, k=k)


# ----------------------------
# 3.0) 公共工具函数
# ----------------------------
def _doc_key(doc: Document) -> str:
    """为融合打分生成稳定 key。优先使用 source + chunk_id。"""
    md = doc.metadata or {}
    source = md.get("source") or md.get("source_url") or "unknown_source"
    chunk_id = md.get("chunk_id") or md.get("id")
    if chunk_id is not None:
        return f"{source}::{chunk_id}"
    # 兜底：使用内容哈希，避免因对象地址不同导致重复
    return f"{source}::{hash(doc.page_content)}"


def _dense_score_to_similarity(score: float) -> float:
    """
    把 FAISS 返回的“距离分数”转成相似度分数（值越大越相似）。
    采用 1 / (1 + distance) 保证非负且单调递减。
    """
    try:
        dist = float(score)
    except (TypeError, ValueError):
        return 0.0
    dist = max(dist, 0.0)
    return 1.0 / (1.0 + dist)


def _normalize_scores(pairs: List[Tuple[Document, float]]) -> List[Tuple[Document, float]]:
    """简单归一化到 [0,1]，避免不同量纲的分数直接相加。"""
    values = [float(s) for _, s in pairs if s is not None]
    if not values:
        return [(doc, 0.0) for doc, _ in pairs]
    min_v, max_v = min(values), max(values)
    if max_v - min_v < 1e-12:
        return [(doc, 1.0) for doc, _ in pairs]
    return [(doc, (float(score) - min_v) / (max_v - min_v)) for doc, score in pairs]


# ----------------------------
# 3.1) BM25 倒排检索（稀疏基线）
#      - 复用 FAISS docstore 里的 Document
#      - 使用简单的中英混合分词（正则切词，无额外依赖）
# ----------------------------
def _tokenize_zh_en(text: str) -> List[str]:
    """
    轻量级分词：把中文串、英文/数字串切分出来，适合 BM25 的倒排。
    如果已有更强的分词器（如 jieba），可在这里替换。
    """
    if not text:
        return []
    return re.findall(r"[\u4e00-\u9fff]+|[a-zA-Z0-9_]+", text.lower())


def build_bm25_retriever(vs: FAISS, k: int = 5) -> BM25Retriever:
    docs = list(getattr(getattr(vs, "docstore", None), "_dict", {}).values())
    if not docs:
        raise ValueError("FAISS docstore 中没有文档，无法构建 BM25 检索器")

    retriever = BM25Retriever.from_documents(docs, preprocess_func=_tokenize_zh_en)
    retriever.k = k
    return retriever


def retrieve_bm25(
    vs: FAISS,
    query: str,
    k: int = 5,
) -> List[Tuple[Document, float]]:
    retriever = build_bm25_retriever(vs, k=k)
    docs = retriever.get_relevant_documents(query)

    results: List[Tuple[Document, float]] = []
    for rank, doc in enumerate(docs, 1):
        # BM25Retriever 会在 metadata 中附带 score；如缺失则用逆排名作为粗略分数
        score = doc.metadata.get("score")
        try:
            score_val = float(score) if score is not None else 1.0 / rank
        except (TypeError, ValueError):
            score_val = 1.0 / rank
        results.append((doc, score_val))
    return results


# ----------------------------
# 3.2) Hybrid 检索（BM25 + 向量）
#      - method="rrf": Reciprocal Rank Fusion，按排名融合，鲁棒且无需调参
#      - method="weighted": 归一化后加权求和，可自定义稠密/稀疏权重
# ----------------------------
def retrieve_hybrid(
    vs: FAISS,
    query: str,
    k: int = 5,
    method: str = "rrf",
    dense_weight: float = 0.6,
    bm25_weight: float = 0.4,
    rrf_k: int = 60,
    candidate_scale: int = 2,
) -> List[Tuple[Document, float]]:
    """
    返回融合后的前 k 条 (Document, score)。
    candidate_scale 用于放宽候选数（如 k=5、candidate_scale=2 -> 先取 10 条再融合），提高覆盖。
    """
    candidate_k = max(k * candidate_scale, k)

    dense_raw = retrieve(vs, query, k=candidate_k)
    dense_results = [(doc, _dense_score_to_similarity(score)) for doc, score in dense_raw]
    bm25_results = retrieve_bm25(vs, query, k=candidate_k)

    dense_sorted = sorted(dense_results, key=lambda x: x[1], reverse=True)
    bm25_sorted = sorted(bm25_results, key=lambda x: x[1], reverse=True)

    method_lc = method.lower()
    if method_lc == "weighted":
        dense_norm = _normalize_scores(dense_sorted)
        bm25_norm = _normalize_scores(bm25_sorted)

        fused: Dict[str, Dict[str, Any]] = {}
        for doc, sc in dense_norm:
            fused[_doc_key(doc)] = {"doc": doc, "score": dense_weight * sc}
        for doc, sc in bm25_norm:
            key = _doc_key(doc)
            if key not in fused:
                fused[key] = {"doc": doc, "score": 0.0}
            fused[key]["score"] += bm25_weight * sc

        merged = sorted(fused.values(), key=lambda x: x["score"], reverse=True)
        return [(item["doc"], item["score"]) for item in merged[:k]]

    # 默认使用 RRF
    fused: Dict[str, Dict[str, Any]] = {}

    def accumulate(results: List[Tuple[Document, float]], weight: float) -> None:
        for rank, (doc, _) in enumerate(results, 1):
            key = _doc_key(doc)
            fused.setdefault(key, {"doc": doc, "score": 0.0})
            fused[key]["score"] += weight / (rrf_k + rank)

    accumulate(dense_sorted, dense_weight)
    accumulate(bm25_sorted, bm25_weight)

    merged = sorted(fused.values(), key=lambda x: x["score"], reverse=True)
    return [(item["doc"], item["score"]) for item in merged[:k]]


# ----------------------------
# 4) 引用结构化：source_url / chunk_id / snippet
# ----------------------------
def build_citations(
    retrieved: List[Tuple[Document, float]],
    snippet_chars: int = 220,
) -> List[Dict[str, Any]]:
    cites: List[Dict[str, Any]] = []
    for i, (doc, score) in enumerate(retrieved, 1):
        md = doc.metadata or {}
        text = (doc.page_content or "").strip()

        # 你入库时 metadata 可能只有 source / chunk_id
        # 如果你后续能提供真实 URL，就放 metadata["source_url"]
        source_url = md.get("source_url") or md.get("source") or "unknown_source"
        chunk_id = md.get("chunk_id", md.get("id", None))

        cites.append(
            {
                "rank": i,
                "score": score,
                "source_url": source_url,
                "chunk_id": chunk_id,
                "content": text,
                "snippet": text[:snippet_chars] + ("..." if len(text) > snippet_chars else ""),
            }
        )
    return cites


# ----------------------------
# 5) 简单的“证据充分性”判断（工程里你可以更严格）
#    - 没检索到：直接证据不足
#    - 检索到了但内容明显不相关：也提示证据不足
# ----------------------------
def evidence_is_weak(query: str, citations: List[Dict[str, Any]]) -> bool:
    if not citations:
        return True

    # 粗糙但实用：看 query 关键词是否在 Top1/Top2 snippet 中出现
    keywords = [w for w in re.split(r"\s+|,|，|。|？|\?|！|!", query) if len(w) >= 2]
    top_snips = " ".join(c["content"] for c in citations[:5])
    hit = 1 if any(kw in top_snips for kw in keywords) else 0
    # 命中太少，就认为证据偏弱（你可以调阈值）
    return hit > 5


# ----------------------------
# 6) 简单冲突检测（演示用）
#    比如同一个问题检索到不同年份/不同截止日期，提示“需人工核对”
# ----------------------------
DATE_RE = re.compile(r"(\d{4})年(\d{1,2})月(\d{1,2})日|(\d{4})年(\d{1,2})月底")

def detect_conflict(citations: List[Dict[str, Any]]) -> Optional[str]:
    found = set()
    for c in citations[:5]:
        for m in DATE_RE.finditer(c["content"]):
            found.add(m.group(0))
    if len(found) >= 2:
        return f"检索到多个可能的日期/期限表述：{sorted(found)}，存在口径不一致风险，建议人工复核原文。"
    return None


# ----------------------------
# 7) RAG 问答：answer + 引用列表
#    - 强约束：只能基于证据回答
#    - 证据不足：明确说明，避免幻觉
# ----------------------------
def rag_qa(
    vs: FAISS,
    question: str,
    k: int = 5,
    retrieval_mode: str = "dense",  # dense: 语义向量；bm25: 倒排；hybrid: 融合
    hybrid_method: str = "rrf",  # rrf | weighted
    dense_weight: float = 0.6,
    bm25_weight: float = 0.4,
    rrf_k: int = 60,
) -> Dict[str, Any]:
    mode = retrieval_mode.lower()
    if mode == "dense":
        retrieved = retrieve(vs, question, k=k)
    elif mode == "bm25":
        retrieved = retrieve_bm25(vs, question, k=k)
    elif mode in {"hybrid", "hybrid_rrf", "hybrid_weighted"}:
        method = "weighted" if mode == "hybrid_weighted" else hybrid_method
        retrieved = retrieve_hybrid(
            vs,
            question,
            k=k,
            method=method,
            dense_weight=dense_weight,
            bm25_weight=bm25_weight,
            rrf_k=rrf_k,
        )
    else:
        raise ValueError(f"不支持的检索模式：{retrieval_mode}，可选 dense / bm25 / hybrid_rrf / hybrid_weighted")

    citations = build_citations(retrieved)

    # 证据不足/不相关：直接拒答或要求补充
    if evidence_is_weak(question, citations):
        return {
            "question": question,
            "answer": "我在当前知识库检索到的证据不足以可靠回答这个问题。你可以补充：政策文件名称/地区/时间范围，或把相关原文段落提供给我，我再基于证据回答。",
            "citations": citations,
            "notes": "evidence_insufficient",
        }

    conflict_note = detect_conflict(citations)

    # 给模型的“证据上下文”
    # 用 [1]/[2] 做标号，方便模型在回答里引用（同时我们也返回结构化引用列表）
    context_lines = []
    for c in citations:
        context_lines.append(
            f"[{c['rank']}] (source={c['source_url']}, chunk_id={c['chunk_id']}) {c['content']}"
        )
    context = "\n".join(context_lines)

    system = SystemMessage(
        content=(
            "你是一个严谨的医疗健康政策问答助手。\n"
            "必须只基于给定【证据】回答，不要编造。\n"
            "如果证据不足，明确说“证据不足”，并说明需要什么信息。\n"
            "回答要简洁；给出结论后，列出引用编号，如：依据[1][2]。\n"
        )
    )
    user = HumanMessage(
        content=(
            f"问题：{question}\n\n"
            f"【证据】\n{context}\n\n"
            "请基于证据作答：\n"
            "1) 先给出结论\n"
            "2) 再给出依据（引用编号）\n"
            "3) 若存在冲突或口径不一致，请提示风险\n"
        )
    )

    resp = chat_model.invoke([system, user])
    answer_text = (resp.content or "").strip()

    # 把冲突提示附加到 notes（也可以让模型自己说，这里双保险）
    notes = []
    if conflict_note:
        notes.append(conflict_note)

    return {
        "question": question,
        "answer": answer_text,
        "citations": citations,  # 结构化引用：source_url/chunk_id/snippet
        "notes": notes,
    }


# ----------------------------
# 8) 模拟多个 QA（多种情况）
# ----------------------------
def run_demo(index_dir: str) -> None:
    vs = load_faiss(index_dir)

    # 同时跑稠密检索与 BM25 倒排基线，方便对比
    modes = ["dense", "bm25", "hybrid_rrf", "hybrid_weighted"]
    questions = [
        # 情况 A：典型“命中明确条款/期限”的问题（你贴的 chunk 里就有 2025年12月底前）
        "商业健康保险创新药品目录是第几年更新的？",
        "佛山生育报销力度",

        # 情况 B：从同一主题多段证据综合（挂网价格/支付标准约束）
        "谈判药品挂网价格与新版目录确定的支付标准有什么关系？",

        # 情况 C：知识库可能没有的“离谱/缺证据”问题 -> 应拒答/提示补充
        "2027年医保目录新增药品必须在1月底前挂网吗？",

        # 情况 D：容易出现“口径冲突/时间不一致”的提问 -> 提醒风险
        "医保定点医疗机构需要在什么时候召开药事会并调整药品配备？",
    ]

    for mode in modes:
        banner = f"{'=' * 25} RETRIEVAL={mode.upper()} {'=' * 25}"
        print("\n" + banner)
        for q in questions:
            result = rag_qa(vs, q, k=5, retrieval_mode=mode)
            print("\n" + "-" * 90)
            print("Q:", result["question"])
            print(f"[模式] {mode}")
            print("\nA:\n", result["answer"])

            print("\n[CITATIONS]")
            for c in result["citations"]:
                print(
                    f"- rank={c['rank']} score={c['score']:.4f} "
                    f"source_url={c['source_url']} chunk_id={c['chunk_id']}\n"
                    f"  snippet: {c['snippet']}\n"
                    f"  content: {c['content']}\n"
                )


if __name__ == "__main__":
    # 改成你保存 FAISS 的目录（里面应有 index.faiss + index.pkl）
    INDEX_DIR = "/Users/lejinbo/LLM/code-snippets/medical_docs/medical_documents_20251217_104142_cleaned_faiss_index"
    run_demo(INDEX_DIR)
