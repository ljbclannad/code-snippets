"""
多路召回 + 重排 + 阈值回退示例。

使用思路：
    python ragTest/multi_route_retriever.py "工作经历"
"""

from __future__ import annotations

import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Sequence, Tuple

from langchain_classic.document_loaders import PyPDFLoader
from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_community.vectorstores import FAISS
from langchain_community.retrievers import BM25Retriever
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_openai.chat_models import ChatOpenAI


@dataclass
class RetrievalResult:
    """封装检索结果与回退信息。"""

    query: str
    documents: List[Document]
    scores: List[float]
    used_fallback: bool
    reason: str


class MultiRouteRetriever:
    """支持多路召回 + 重排 + 阈值回退的检索器。"""

    def __init__(
        self,
        pdf_path: Path,
        embeddings: DashScopeEmbeddings,
        dense_k: int = 4,
        sparse_k: int = 4,
        final_k: int = 3,
        score_threshold: float = 0.32,
        chat_model: Optional[ChatOpenAI] = None,
        fallback_text: str = (
            "未在文档中检索到与“{query}”高度相关的内容，请尝试换个说法或"
            "提供更多上下文。"
        ),
    ) -> None:
        self.pdf_path = pdf_path
        self.embeddings = embeddings
        self.dense_k = dense_k
        self.sparse_k = sparse_k
        self.final_k = final_k
        self.score_threshold = score_threshold
        self.chat_model = chat_model
        self.fallback_text = fallback_text

        self.text_splitter = RecursiveCharacterTextSplitter(
            chunk_size=900,
            chunk_overlap=120,
            add_start_index=True,
        )
        self._prepare_corpus()

    def search(self, query: str) -> RetrievalResult:
        """执行多路召回、重排并附带阈值回退。"""
        candidates = self._multi_recall(query)
        if not candidates:
            return self._fallback(query, "多路召回返回空结果")

        reranked = self._rerank(query, candidates)
        if not reranked:
            return self._fallback(query, "重排阶段没有可用文档")

        top_score = reranked[0][1]
        if top_score < self.score_threshold:
            reason = (
                f"最高得分 {top_score:.3f} 低于阈值 {self.score_threshold:.2f}，触发回退"
            )
            return self._fallback(query, reason)

        docs = [doc for doc, _ in reranked[: self.final_k]]
        scores = [score for _, score in reranked[: self.final_k]]
        return RetrievalResult(
            query=query,
            documents=docs,
            scores=scores,
            used_fallback=False,
            reason="命中多路召回并通过相似度重排",
        )

    def _prepare_corpus(self) -> None:
        if not self.pdf_path.exists():
            raise FileNotFoundError(f"未找到 PDF 文件：{self.pdf_path}")

        loader = PyPDFLoader(str(self.pdf_path))
        docs = loader.load()
        chunks = self.text_splitter.split_documents(docs)
        if not chunks:
            raise ValueError("文档切分后没有可用文本。")

        self.vector_store = FAISS.from_documents(chunks, self.embeddings)
        self.vector_retriever = self.vector_store.as_retriever(
            search_kwargs={"k": self.dense_k}
        )

        self.sparse_retriever = BM25Retriever.from_documents(chunks)
        self.sparse_retriever.k = self.sparse_k

    def _multi_recall(self, query: str) -> List[Document]:
        dense_docs = self.vector_retriever.get_relevant_documents(query)
        sparse_docs = self.sparse_retriever.get_relevant_documents(query)
        return self._deduplicate(dense_docs + sparse_docs)

    def _rerank(
        self,
        query: str,
        documents: Sequence[Document],
    ) -> List[Tuple[Document, float]]:
        query_vec = self.embeddings.embed_query(query)
        scored: List[Tuple[Document, float]] = []

        for doc in documents:
            vector = doc.metadata.get("_dense_vector")
            if vector is None:
                vector = self.embeddings.embed_documents([doc.page_content])[0]
                doc.metadata["_dense_vector"] = vector
            score = self._cosine_similarity(query_vec, vector)
            scored.append((doc, score))

        scored.sort(key=lambda item: item[1], reverse=True)
        return scored

    @staticmethod
    def _cosine_similarity(a: Sequence[float], b: Sequence[float]) -> float:
        dot = sum(x * y for x, y in zip(a, b))
        norm_a = math.sqrt(sum(x * x for x in a)) or 1e-9
        norm_b = math.sqrt(sum(y * y for y in b)) or 1e-9
        return dot / (norm_a * norm_b)

    @staticmethod
    def _deduplicate(documents: Sequence[Document]) -> List[Document]:
        unique: List[Document] = []
        seen = set()
        for doc in documents:
            key = (
                doc.metadata.get("source"),
                doc.metadata.get("page"),
                doc.metadata.get("start_index"),
                doc.page_content[:40],
            )
            if key in seen:
                continue
            seen.add(key)
            unique.append(doc)
        return unique

    def _fallback(self, query: str, reason: str) -> RetrievalResult:
        if self.chat_model is not None:
            messages = [
                {
                    "role": "system",
                    "content": "你是一个在缺少检索结果时解释原因的助手。",
                },
                {
                    "role": "user",
                    "content": (
                        f"检索查询“{query}”没有找到可信内容，原因：{reason}。\n"
                        "请给用户一个自然语言解释，并提示如何改写问题。"
                    ),
                },
            ]
            response = self.chat_model.invoke(messages)
            fallback_text = response.content.strip()
        else:
            fallback_text = self.fallback_text.format(query=query)

        fallback_doc = Document(
            page_content=fallback_text,
            metadata={"source": "fallback", "reason": reason},
        )
        return RetrievalResult(
            query=query,
            documents=[fallback_doc],
            scores=[],
            used_fallback=True,
            reason=reason,
        )


def demo(query: str) -> None:
    """快速运行示例，打印检索输出。"""
    base_dir = Path(__file__).resolve().parent
    pdf_path = base_dir / "test.pdf"

    embeddings = DashScopeEmbeddings(model="text-embedding-v4")
    chat_model = ChatOpenAI(
        api_key=os.getenv("DEEPSEEK_API_KEY"),
        model="deepseek-chat",
        base_url="https://api.deepseek.com",
        temperature=0,
    )

    retriever = MultiRouteRetriever(
        pdf_path=pdf_path,
        embeddings=embeddings,
        chat_model=chat_model,
        score_threshold=0.35,
    )

    result = retriever.search(query)
    header = "[FALLBACK]" if result.used_fallback else "[RESULT]"
    print(f"{header} {result.reason}")
    for idx, (doc, score) in enumerate(zip(result.documents, result.scores or []), start=1):
        snippet = doc.page_content[:80].replace("\n", " ")
        print(f"{idx}. score={score:.3f} snippet={snippet}...")

    if result.used_fallback and result.documents:
        print(result.documents[0].page_content)


if __name__ == "__main__":
    import sys

    user_query = sys.argv[1] if len(sys.argv) > 1 else "工作经历"
    demo(user_query)

