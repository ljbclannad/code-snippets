
import json
import os
import re
from typing import Any, Dict, List, Tuple, Optional

from langchain_openai.chat_models import ChatOpenAI
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.retrievers import BM25Retriever
from langchain_community.vectorstores import FAISS
from langchain_core.documents import Document
from langchain_core.messages import SystemMessage, HumanMessage

from reranker import CrossEncoderReranker, LLMReranker, Reranker

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
# 3.3) 检索后过滤/重排
#      - 硬过滤：直接剔除不满足约束的候选
#      - 软过滤：调整权重/重排，不直接丢弃
# ----------------------------
def apply_hard_filters(
    retrieved: List[Tuple[Document, float]],
    min_score: Optional[float] = None,
    allowed_lang: Optional[List[str]] = None,
    must_tags: Optional[List[str]] = None,
    min_len: int = 0,
    banned_sources: Optional[List[str]] = None,
) -> List[Tuple[Document, float]]:
    kept: List[Tuple[Document, float]] = []
    allow_lang_set = set(allowed_lang) if allowed_lang else None
    must_tag_set = set(must_tags) if must_tags else None
    banned_source_set = set(banned_sources) if banned_sources else None

    for doc, score in retrieved:
        md = doc.metadata or {}
        text = (doc.page_content or "").strip()

        if min_score is not None and score < min_score:
            continue

        if allow_lang_set is not None:
            lang = md.get("lang") or md.get("language")
            if lang not in allow_lang_set:
                continue

        if must_tag_set:
            tags = set(md.get("tags") or [])
            if not must_tag_set.issubset(tags):
                continue

        if min_len and len(text) < min_len:
            continue

        if banned_source_set:
            src = md.get("source") or md.get("source_url")
            if src in banned_source_set:
                continue

        kept.append((doc, score))

    return kept


def apply_soft_rerank(
    retrieved: List[Tuple[Document, float]],
    alpha: float = 0.7,
) -> List[Tuple[Document, float]]:
    """
    软过滤：在不丢弃候选的情况下调权重，综合质量/时效等特征后重排。
    alpha 越大越依赖原检索分数。
    """
    reranked: List[Tuple[Document, float]] = []
    for doc, score in retrieved:
        md = doc.metadata or {}
        quality = float(md.get("quality_score", 0.5) or 0.5)
        freshness = float(md.get("fresh_score", 0.5) or 0.5)
        bonus = 0.5 * (quality + freshness)
        new_score = alpha * float(score) + (1 - alpha) * bonus
        reranked.append((doc, new_score))

    reranked.sort(key=lambda x: x[1], reverse=True)
    return reranked


def _split_sentences(text: str) -> List[str]:
    """轻量句子切分，中英文标点皆可。"""
    if not text:
        return []
    parts = re.split(r"[。！!？\?；;]|[\r\n]+", text)
    return [p.strip() for p in parts if p.strip()]


def _split_paragraphs(text: str) -> List[str]:
    """按空行/多行分隔切分段落。"""
    if not text:
        return []
    parts = re.split(r"\n\s*\n+", text)
    return [p.strip() for p in parts if p.strip()]


def _keyword_list(query: str) -> List[str]:
    return [w for w in re.split(r"\s+|,|，|。|？|\?|！|!", query) if len(w) >= 2]


def _score_text(text: str, keywords: List[str]) -> int:
    return sum(1 for kw in keywords if kw in text)


def _extract_snippet_sentences(text: str, query: str, max_chars: int = 600) -> str:
    """
    句子抽取：基于关键词选句，控制长度。
    """
    if not text:
        return ""
    if len(text) <= max_chars:
        return text.strip()

    keywords = _keyword_list(query)
    sentences = _split_sentences(text)
    if not sentences:
        return text[:max_chars]

    scored = []
    for sent in sentences:
        score = sum(1 for kw in keywords if kw in sent)
        scored.append((score, sent))

    # 先按匹配度降序，再按长度升序，尽量多塞入关键词句
    scored.sort(key=lambda x: (-x[0], len(x[1])))

    buf: List[str] = []
    total = 0
    for score, sent in scored:
        if total + len(sent) > max_chars:
            continue
        buf.append(sent)
        total += len(sent)
        # 若已包含全部关键词，可提前结束
        if all(kw in " ".join(buf) for kw in keywords):
            break

    if not buf:
        return text[:max_chars]
    return "。".join(buf)


def _extract_snippet_paragraphs(text: str, query: str, max_chars: int = 600) -> str:
    """
    段落裁剪：优先保留与 query 关键词匹配的段落。
    """
    if not text:
        return ""
    if len(text) <= max_chars:
        return text.strip()

    paragraphs = _split_paragraphs(text)
    if not paragraphs:
        return text[:max_chars]

    keywords = _keyword_list(query)
    scored = [( _score_text(p, keywords), p) for p in paragraphs]
    scored.sort(key=lambda x: (-x[0], len(x[1])))

    buf: List[str] = []
    total = 0
    for score, para in scored:
        if total + len(para) > max_chars:
            continue
        buf.append(para)
        total += len(para)
        if all(kw in "\n".join(buf) for kw in keywords):
            break

    if not buf:
        return text[:max_chars]
    return "\n\n".join(buf)


def _extract_snippet_hybrid(text: str, query: str, max_chars: int = 600) -> str:
    """
    先段落裁剪，再在候选段落内做句子抽取，保证可读性与相关性。
    """
    para = _extract_snippet_paragraphs(text, query, max_chars=max_chars)
    return _extract_snippet_sentences(para, query, max_chars=max_chars)


def compress_context(
    retrieved: List[Tuple[Document, float]],
    query: str,
    max_total_chars: int = 3200,
    max_per_doc: int = 800,
    strategy: str = "sentences",
) -> List[Tuple[Document, float]]:
    """
    对检索结果做上下文压缩，限制总字数与单 doc 字数。
    - 句子抽取 / 段落裁剪 / 混合策略
    - 截断总长度，降低上下文窗口占用
    """
    compressed: List[Tuple[Document, float]] = []
    remaining = max_total_chars
    strategy_lc = (strategy or "sentences").lower()

    for doc, score in retrieved:
        if remaining <= 0:
            break
        raw_text = doc.page_content or ""
        max_chars = min(max_per_doc, remaining)
        if strategy_lc in {"paragraphs", "para"}:
            snippet = _extract_snippet_paragraphs(raw_text, query, max_chars=max_chars)
        elif strategy_lc in {"hybrid", "mix"}:
            snippet = _extract_snippet_hybrid(raw_text, query, max_chars=max_chars)
        else:
            snippet = _extract_snippet_sentences(raw_text, query, max_chars=max_chars)
        new_doc = Document(page_content=snippet, metadata=doc.metadata)
        compressed.append((new_doc, score))
        remaining -= len(snippet)

    return compressed


# Dense 检索返回的是“距离”分数，需转相似度后再做阈值判断/重排
def _convert_dense_scores_if_needed(
    retrieved: List[Tuple[Document, float]],
    mode: str,
) -> List[Tuple[Document, float]]:
    if mode != "dense":
        return retrieved
    return [(doc, _dense_score_to_similarity(score)) for doc, score in retrieved]


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


def _build_evidence_from_citations(citations: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    evidence = []
    for c in citations:
        evidence.append(
            {
                "引用": f"[{c['rank']}]",
                "source_url": c.get("source_url"),
                "chunk_id": c.get("chunk_id"),
                "snippet": c.get("snippet") or c.get("content", "")[:220],
            }
        )
    return evidence


def _extract_json_block(text: str) -> Optional[str]:
    """
    从模型输出中提取 JSON 块（容忍前后多余文本）。
    """
    if not text:
        return None
    text = text.strip()
    if text.startswith("{") and text.endswith("}"):
        return text
    match = re.search(r"\{.*\}", text, flags=re.DOTALL)
    return match.group(0) if match else None


def validate_structured_json(
    payload: Any,
    citations: Optional[List[Dict[str, Any]]] = None,
) -> Tuple[bool, List[str]]:
    """
    校验结构化输出：
    - 必须包含：适用 / 不适用 / 证据
    - 适用、不适用为字符串
    - 证据为数组，元素必须是 dict 且包含引用
    - 若提供 citations，引用必须在已有 rank 中
    """
    errors: List[str] = []
    if not isinstance(payload, dict):
        return False, ["payload 不是对象"]

    for key in ["适用", "不适用", "证据"]:
        if key not in payload:
            errors.append(f"缺少字段: {key}")

    if "适用" in payload and not isinstance(payload["适用"], str):
        errors.append("适用 必须是字符串")
    if "不适用" in payload and not isinstance(payload["不适用"], str):
        errors.append("不适用 必须是字符串")

    if "证据" in payload:
        if not isinstance(payload["证据"], list):
            errors.append("证据 必须是数组")
        else:
            valid_refs = set()
            if citations:
                valid_refs = {f"[{c['rank']}]" for c in citations}
            for idx, item in enumerate(payload["证据"]):
                if not isinstance(item, dict):
                    errors.append(f"证据[{idx}] 不是对象")
                    continue
                if "引用" not in item:
                    errors.append(f"证据[{idx}] 缺少引用")
                    continue
                if citations and item.get("引用") not in valid_refs:
                    errors.append(f"证据[{idx}] 引用无效: {item.get('引用')}")

    return len(errors) == 0, errors


def parse_and_validate_structured_json(
    text: str,
    citations: Optional[List[Dict[str, Any]]] = None,
) -> Tuple[Optional[Dict[str, Any]], List[str]]:
    block = _extract_json_block(text)
    if not block:
        return None, ["未找到 JSON 块"]
    try:
        payload = json.loads(block)
    except json.JSONDecodeError as e:
        return None, [f"JSON 解析失败: {e}"]
    ok, errors = validate_structured_json(payload, citations=citations)
    if not ok:
        return payload, errors
    return payload, []


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
    hard_filters: Optional[Dict[str, Any]] = None,
    soft_rerank_alpha: Optional[float] = None,
    cross_encoder_model: Optional[str] = None,
    cross_encoder_batch_size: int = 16,
    cross_encoder_top_k: Optional[int] = None,
    reranker: Optional[Reranker] = None,
    rerank_top_k: Optional[int] = None,
    llm_rerank: bool = False,
    llm_rerank_batch_size: int = 5,
    llm_rerank_max_chars: int = 800,
    llm_rerank_blend_weight: float = 0.85,
    enable_context_compress: bool = False,
    context_max_total_chars: int = 3200,
    context_max_per_doc: int = 800,
    context_strategy: str = "sentences",
    structured_output: bool = False,
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

    # 先将 dense 路径的距离分转成相似度分，方便阈值判断
    retrieved = _convert_dense_scores_if_needed(retrieved, mode)

    # 硬过滤：不符合条件的直接剔除
    if hard_filters:
        retrieved = apply_hard_filters(
            retrieved,
            min_score=hard_filters.get("min_score"),
            allowed_lang=hard_filters.get("allowed_lang"),
            must_tags=hard_filters.get("must_tags"),
            min_len=hard_filters.get("min_len", 0),
            banned_sources=hard_filters.get("banned_sources"),
        )

    # 软过滤/重排：调权重，不直接丢弃
    if soft_rerank_alpha is not None:
        retrieved = apply_soft_rerank(retrieved, alpha=soft_rerank_alpha)

    # Reranker 接口：优先使用外部传入的 reranker
    if reranker is not None:
        retrieved = reranker.rerank(question, retrieved, top_k=rerank_top_k or k)
    # LLM rerank（按需开启）
    elif llm_rerank:
        llm_reranker = LLMReranker(
            chat_model=chat_model,
            batch_size=llm_rerank_batch_size,
            max_chars_per_doc=llm_rerank_max_chars,
            blend_weight=llm_rerank_blend_weight,
        )
        retrieved = llm_reranker.rerank(question, retrieved, top_k=rerank_top_k or k)
    # CrossEncoder 语义重排：计算 query-doc 配对分数，提升相关性排序
    elif cross_encoder_model:
        ce_reranker = CrossEncoderReranker(
            model_name=cross_encoder_model,
            batch_size=cross_encoder_batch_size,
        )
        retrieved = ce_reranker.rerank(question, retrieved, top_k=cross_encoder_top_k or k)

    # 可选：上下文压缩，减少无关或冗长内容
    if enable_context_compress:
        retrieved = compress_context(
            retrieved,
            question,
            max_total_chars=context_max_total_chars,
            max_per_doc=context_max_per_doc,
            strategy=context_strategy,
        )

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

    if structured_output:
        system = SystemMessage(
            content=(
                "你是一个严谨的医疗健康政策问答助手。\n"
                "必须只基于给定【证据】回答，不要编造。\n"
                "输出必须为 JSON 对象，且只输出 JSON。\n"
                "必须包含字段：适用、不适用、证据。\n"
                "证据为数组，元素对象至少包含：引用。\n"
                "引用值必须使用形如 [1]、[2] 的编号。\n"
            )
        )
        user = HumanMessage(
            content=(
                f"问题：{question}\n\n"
                f"【证据】\n{context}\n\n"
                "请输出 JSON：\n"
                "适用：给出适用场景/条件与结论（无则给空字符串）\n"
                "不适用：给出不适用场景/排除条件（无则给空字符串）\n"
                "证据：列出支撑结论的引用编号（可带简短说明）\n"
            )
        )
    else:
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

    if structured_output:
        structured, errors = parse_and_validate_structured_json(answer_text, citations=citations)
        if structured is None or errors:
            fallback = {
                "适用": "",
                "不适用": "",
                "证据": _build_evidence_from_citations(citations),
            }
            return {
                "question": question,
                "answer": answer_text,
                "structured": structured or fallback,
                "citations": citations,
                "notes": notes,
                "validation_errors": errors,
            }
        return {
            "question": question,
            "answer": answer_text,
            "structured": structured,
            "citations": citations,
            "notes": notes,
        }

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
