from __future__ import annotations

import importlib
import json
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional, Protocol, Tuple

from langchain_core.documents import Document
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage


class Reranker(Protocol):
    """Reranker 接口：输入 query + 候选文档，输出重排后的 (doc, score)。"""

    def rerank(
        self,
        query: str,
        candidates: List[Tuple[Document, float]],
        top_k: Optional[int] = None,
    ) -> List[Tuple[Document, float]]: ...


_CROSS_ENCODER_CACHE: Dict[str, Any] = {}


def _load_cross_encoder(model_name: str) -> Any:
    try:
        ce_module = importlib.import_module("sentence_transformers")
        CrossEncoder = getattr(ce_module, "CrossEncoder")
    except ImportError as e:
        raise ImportError("请先 pip install sentence-transformers 以启用 CrossEncoder rerank") from e

    if model_name not in _CROSS_ENCODER_CACHE:
        _CROSS_ENCODER_CACHE[model_name] = CrossEncoder(model_name, trust_remote_code=True)
    return _CROSS_ENCODER_CACHE[model_name]


@dataclass
class CrossEncoderReranker:
    model_name: str = "BAAI/bge-reranker-base"
    batch_size: int = 16

    def rerank(
        self,
        query: str,
        candidates: List[Tuple[Document, float]],
        top_k: Optional[int] = None,
    ) -> List[Tuple[Document, float]]:
        if not candidates:
            return []

        ce = _load_cross_encoder(self.model_name)
        pairs = [(query, doc.page_content or "") for doc, _ in candidates]
        scores = ce.predict(pairs, batch_size=self.batch_size, show_progress_bar=False)

        reranked = [(doc, float(score)) for (doc, _), score in zip(candidates, scores)]
        reranked.sort(key=lambda x: x[1], reverse=True)
        return reranked[:top_k] if top_k is not None else reranked


def _truncate(text: str, max_chars: int) -> str:
    text = (text or "").strip()
    if len(text) <= max_chars:
        return text
    return text[:max_chars]


def _chunked(items: List[Any], size: int) -> Iterable[List[Any]]:
    for i in range(0, len(items), size):
        yield items[i : i + size]


@dataclass
class LLMReranker:
    """
    LLM 重排：把 query + 候选文本发给模型，返回相关性分数。
    - 适合高相关性排序，但成本更高
    - 支持批量评分，减少请求次数
    """

    chat_model: BaseChatModel
    batch_size: int = 5
    max_chars_per_doc: int = 800
    blend_weight: float = 0.85  # 与原始分数做加权融合，提升稳定性

    def _build_prompt(self, query: str, docs: List[Document]) -> List[Any]:
        payload = [
            {"id": i + 1, "content": _truncate(doc.page_content or "", self.max_chars_per_doc)}
            for i, doc in enumerate(docs)
        ]
        system = SystemMessage(
            content=(
                "你是医疗检索重排模型。"
                "请根据问题与候选文本的相关性打分，输出 JSON 数组，"
                "每个元素形如 {\"id\":1,\"score\":0.0}。"
                "score 取值范围 0~1，越高越相关。"
                "只输出 JSON，不要附加其他说明。"
            )
        )
        user = HumanMessage(
            content=(
                f"问题：{query}\n\n"
                f"候选：{json.dumps(payload, ensure_ascii=False)}\n\n"
                "请严格按候选顺序给出分数。"
            )
        )
        return [system, user]

    def _parse_scores(self, raw: str, expected_len: int) -> Optional[List[float]]:
        try:
            data = json.loads(raw)
            if not isinstance(data, list):
                return None
            scores: List[float] = []
            for item in data:
                if not isinstance(item, dict) or "score" not in item:
                    return None
                scores.append(float(item["score"]))
            if len(scores) != expected_len:
                return None
            return scores
        except (ValueError, TypeError, json.JSONDecodeError):
            return None

    def rerank(
        self,
        query: str,
        candidates: List[Tuple[Document, float]],
        top_k: Optional[int] = None,
    ) -> List[Tuple[Document, float]]:
        if not candidates:
            return []

        scored: List[Tuple[Document, float]] = []
        for batch in _chunked(candidates, max(self.batch_size, 1)):
            docs = [doc for doc, _ in batch]
            messages = self._build_prompt(query, docs)
            resp = self.chat_model.invoke(messages)
            raw = (resp.content or "").strip()
            scores = self._parse_scores(raw, len(batch))

            if scores is None:
                # 降级：保留原始分数，避免硬失败
                scored.extend(batch)
                continue

            for (doc, base_score), llm_score in zip(batch, scores):
                llm_score = max(0.0, min(1.0, float(llm_score)))
                fused = self.blend_weight * llm_score + (1 - self.blend_weight) * float(base_score)
                scored.append((doc, fused))

        scored.sort(key=lambda x: x[1], reverse=True)
        return scored[:top_k] if top_k is not None else scored
