from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

from langchain_core.documents import Document

from medical_model_chat import (
    _convert_dense_scores_if_needed,
    get_chat_model,
    load_faiss,
    retrieve,
    retrieve_bm25,
    retrieve_hybrid,
)
from reranker import CrossEncoderReranker, LLMReranker, Reranker


def _load_json_or_jsonl(path: Path) -> List[Dict[str, Any]]:
    if not path.exists():
        raise FileNotFoundError(f"找不到评测数据文件: {path}")
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() in {".jsonl", ".jl"}:
        items = []
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            items.append(json.loads(line))
        return items
    data = json.loads(text)
    if not isinstance(data, list):
        raise ValueError("评测数据 JSON 顶层必须是 list")
    return data


def _doc_key(doc: Document) -> str:
    md = doc.metadata or {}
    source = (
        md.get("source")
        or md.get("source_url")
        or md.get("source_name")
        or md.get("source_file")
        or "unknown_source"
    )
    chunk_id = md.get("chunk_id", md.get("id", None))
    if chunk_id is not None:
        return f"{source}::{chunk_id}"
    return f"{source}::{hash(doc.page_content)}"


def _doc_chunk_id(doc: Document) -> Optional[str]:
    md = doc.metadata or {}
    chunk_id = md.get("chunk_id", md.get("id", None))
    return str(chunk_id) if chunk_id is not None else None


def _parse_gold(item: Dict[str, Any]) -> Tuple[str, List[str], List[str]]:
    query = item.get("query") or item.get("question")
    if not query:
        raise ValueError("每条评测样本必须包含 query 或 question")

    gold_keys: List[str] = []
    gold_chunk_ids: List[str] = []

    if isinstance(item.get("relevant_doc_ids"), list):
        gold_keys = [str(x) for x in item["relevant_doc_ids"]]
    if isinstance(item.get("relevant"), list):
        for obj in item["relevant"]:
            if not isinstance(obj, dict):
                continue
            source = obj.get("source") or obj.get("source_url") or obj.get("source_name")
            chunk_id = obj.get("chunk_id", obj.get("id", None))
            if source and chunk_id is not None:
                gold_keys.append(f"{source}::{chunk_id}")
    if isinstance(item.get("relevant_chunk_ids"), list):
        gold_chunk_ids = [str(x) for x in item["relevant_chunk_ids"]]

    # 允许用单独的 source + chunk_ids
    if gold_chunk_ids and item.get("source"):
        src = str(item["source"])
        gold_keys.extend([f"{src}::{cid}" for cid in gold_chunk_ids])

    # 去重
    gold_keys = list(dict.fromkeys(gold_keys))
    gold_chunk_ids = list(dict.fromkeys(gold_chunk_ids))

    if not gold_keys and not gold_chunk_ids:
        raise ValueError("评测样本缺少 relevant_doc_ids/relevant/relevant_chunk_ids")

    return query, gold_keys, gold_chunk_ids


def _is_relevant(
    doc: Document,
    gold_keys: Sequence[str],
    gold_chunk_ids: Sequence[str],
) -> bool:
    if gold_keys:
        return _doc_key(doc) in set(gold_keys)
    if gold_chunk_ids:
        cid = _doc_chunk_id(doc)
        return cid is not None and cid in set(gold_chunk_ids)
    return False


def _evaluate_one(
    query: str,
    gold_keys: Sequence[str],
    gold_chunk_ids: Sequence[str],
    retrieved: List[Tuple[Document, float]],
    ks: Sequence[int],
) -> Dict[str, Any]:
    max_k = max(ks)
    docs = [doc for doc, _ in retrieved[:max_k]]

    # rank of first relevant
    first_rank = None
    for idx, doc in enumerate(docs, 1):
        if _is_relevant(doc, gold_keys, gold_chunk_ids):
            first_rank = idx
            break

    results: Dict[str, Any] = {
        "query": query,
        "gold_count": len(gold_keys) if gold_keys else len(gold_chunk_ids),
        "first_rank": first_rank,
    }

    for k in ks:
        top_docs = docs[:k]
        hit = 1 if any(_is_relevant(d, gold_keys, gold_chunk_ids) for d in top_docs) else 0
        hit_count = sum(1 for d in top_docs if _is_relevant(d, gold_keys, gold_chunk_ids))
        denom = len(gold_keys) if gold_keys else len(gold_chunk_ids)
        recall = hit_count / denom if denom else 0.0
        mrr = (1.0 / first_rank) if (first_rank is not None and first_rank <= k) else 0.0
        results[f"hit@{k}"] = hit
        results[f"recall@{k}"] = recall
        results[f"mrr@{k}"] = mrr

    return results


def _mean(values: Iterable[float]) -> float:
    vals = list(values)
    return sum(vals) / len(vals) if vals else 0.0


def _build_reranker(args: argparse.Namespace) -> Optional[Reranker]:
    if args.rerank == "cross_encoder":
        return CrossEncoderReranker(
            model_name=args.cross_encoder_model,
            batch_size=args.cross_encoder_batch_size,
        )
    if args.rerank == "llm":
        return LLMReranker(
            chat_model=get_chat_model(),
            batch_size=args.llm_rerank_batch_size,
            max_chars_per_doc=args.llm_rerank_max_chars,
            blend_weight=args.llm_rerank_blend_weight,
        )
    return None


def evaluate_retrieval(
    *,
    index_dir: str,
    data: str,
    ks: Sequence[int],
    retrieval_mode: str = "dense",
    hybrid_method: str = "rrf",
    dense_weight: float = 0.6,
    bm25_weight: float = 0.4,
    rrf_k: int = 60,
    candidate_scale: int = 2,
    rerank: str = "none",
    rerank_top_k: Optional[int] = None,
    cross_encoder_model: str = "BAAI/bge-reranker-base",
    cross_encoder_batch_size: int = 16,
    llm_rerank_batch_size: int = 5,
    llm_rerank_max_chars: int = 800,
    llm_rerank_blend_weight: float = 0.85,
) -> Dict[str, Any]:
    ks = sorted({int(x) for x in ks})
    if not ks:
        raise ValueError("ks 不能为空")

    args = argparse.Namespace(
        index_dir=index_dir,
        data=data,
        retrieval_mode=retrieval_mode,
        hybrid_method=hybrid_method,
        dense_weight=dense_weight,
        bm25_weight=bm25_weight,
        rrf_k=rrf_k,
        candidate_scale=candidate_scale,
        rerank=rerank,
        rerank_top_k=rerank_top_k,
        cross_encoder_model=cross_encoder_model,
        cross_encoder_batch_size=cross_encoder_batch_size,
        llm_rerank_batch_size=llm_rerank_batch_size,
        llm_rerank_max_chars=llm_rerank_max_chars,
        llm_rerank_blend_weight=llm_rerank_blend_weight,
    )

    vs = load_faiss(index_dir)
    items = _load_json_or_jsonl(Path(data))
    reranker = _build_reranker(args)
    max_k = max(ks)

    per_query: List[Dict[str, Any]] = []
    start = time.time()

    for item in items:
        query, gold_keys, gold_chunk_ids = _parse_gold(item)

        mode = retrieval_mode.lower()
        if mode == "dense":
            retrieved = retrieve(vs, query, k=max_k * candidate_scale)
        elif mode == "bm25":
            retrieved = retrieve_bm25(vs, query, k=max_k * candidate_scale)
        elif mode in {"hybrid", "hybrid_rrf", "hybrid_weighted"}:
            method = "weighted" if mode == "hybrid_weighted" else hybrid_method
            retrieved = retrieve_hybrid(
                vs,
                query,
                k=max_k,
                method=method,
                dense_weight=dense_weight,
                bm25_weight=bm25_weight,
                rrf_k=rrf_k,
                candidate_scale=candidate_scale,
            )
        else:
            raise ValueError(f"不支持的检索模式: {retrieval_mode}")

        retrieved = _convert_dense_scores_if_needed(retrieved, mode)

        if reranker is not None:
            retrieved = reranker.rerank(query, retrieved, top_k=rerank_top_k or max_k)

        result = _evaluate_one(query, gold_keys, gold_chunk_ids, retrieved, ks)
        per_query.append(result)

    elapsed = time.time() - start

    agg: Dict[str, Any] = {
        "num_queries": len(per_query),
        "elapsed_sec": round(elapsed, 3),
        "ks": ks,
    }
    for k in ks:
        agg[f"recall@{k}"] = _mean(r[f"recall@{k}"] for r in per_query)
        agg[f"mrr@{k}"] = _mean(r[f"mrr@{k}"] for r in per_query)
        agg[f"hit@{k}"] = _mean(r[f"hit@{k}"] for r in per_query)

    return {
        "meta": {
            "index_dir": index_dir,
            "data": data,
            "retrieval_mode": retrieval_mode,
            "rerank": rerank,
            "cross_encoder_model": cross_encoder_model,
            "llm_rerank": rerank == "llm",
        },
        "aggregate": agg,
        "per_query": per_query,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="RAG 检索评测 v1：Recall@K / MRR / 命中率")
    parser.add_argument("--index-dir", required=True, help="FAISS 索引目录（含 index.faiss/index.pkl）")
    parser.add_argument("--data", required=True, help="评测集 JSON/JSONL 路径")
    parser.add_argument("--output", required=True, help="输出报告 JSON 路径")
    parser.add_argument("--ks", default="1,3,5,10", help="K 列表，如 1,3,5,10")
    parser.add_argument("--retrieval-mode", default="dense", help="dense/bm25/hybrid_rrf/hybrid_weighted")
    parser.add_argument("--hybrid-method", default="rrf", help="rrf/weighted")
    parser.add_argument("--dense-weight", type=float, default=0.6)
    parser.add_argument("--bm25-weight", type=float, default=0.4)
    parser.add_argument("--rrf-k", type=int, default=60)
    parser.add_argument("--candidate-scale", type=int, default=2)

    parser.add_argument(
        "--rerank",
        choices=["none", "cross_encoder", "llm"],
        default="none",
        help="是否开启重排",
    )
    parser.add_argument("--rerank-top-k", type=int, default=None)
    parser.add_argument("--cross-encoder-model", default="BAAI/bge-reranker-base")
    parser.add_argument("--cross-encoder-batch-size", type=int, default=16)
    parser.add_argument("--llm-rerank-batch-size", type=int, default=5)
    parser.add_argument("--llm-rerank-max-chars", type=int, default=800)
    parser.add_argument("--llm-rerank-blend-weight", type=float, default=0.85)
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    ks = sorted({int(x) for x in args.ks.split(",") if x.strip()})
    if not ks:
        raise SystemExit("ks 不能为空")

    report = evaluate_retrieval(
        index_dir=args.index_dir,
        data=args.data,
        ks=ks,
        retrieval_mode=args.retrieval_mode,
        hybrid_method=args.hybrid_method,
        dense_weight=args.dense_weight,
        bm25_weight=args.bm25_weight,
        rrf_k=args.rrf_k,
        candidate_scale=args.candidate_scale,
        rerank=args.rerank,
        rerank_top_k=args.rerank_top_k,
        cross_encoder_model=args.cross_encoder_model,
        cross_encoder_batch_size=args.cross_encoder_batch_size,
        llm_rerank_batch_size=args.llm_rerank_batch_size,
        llm_rerank_max_chars=args.llm_rerank_max_chars,
        llm_rerank_blend_weight=args.llm_rerank_blend_weight,
    )

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[DONE] report saved: {out_path}")


if __name__ == "__main__":
    main()
