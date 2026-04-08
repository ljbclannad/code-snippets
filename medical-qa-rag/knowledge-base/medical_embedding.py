from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any, Dict, List

from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.vectorstores import FAISS
from langchain_core.documents import Document

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DOCS_DIR = Path(os.getenv("MEDICAL_DOCS_DIR", REPO_ROOT / "medical_docs")).expanduser()
DEFAULT_EMBEDDING_MODEL = os.getenv("MEDICAL_QA_EMBEDDING_MODEL", "text-embedding-v4")


def load_chunks(json_path: Path) -> List[Dict[str, Any]]:
    """
    读取分块后的 JSON 文件，格式示例：
    [
      {"id": 3, "chunk": "..."},
      {"id": 4, "chunk": "..."}
    ]
    """
    with json_path.open("r", encoding="utf-8") as f:
        data = json.load(f)

    if not isinstance(data, list):
        raise ValueError(f"JSON 顶层必须是 list，实际是：{type(data)}")

    cleaned = []
    for item in data:
        if not isinstance(item, dict):
            continue
        chunk = (item.get("chunk") or "").strip()
        if chunk:
            cleaned.append(item)
    return cleaned


def _metadata_value(item: Dict[str, Any], *keys: str, default: Any = None) -> Any:
    for key in keys:
        value = item.get(key)
        if value not in (None, ""):
            return value
    return default


def build_documents(chunks: List[Dict[str, Any]], source_name: str) -> List[Document]:
    """
    把 chunk 转成 LangChain Document，方便后续存入向量库。
    """
    docs: List[Document] = []
    for item in chunks:
        chunk_id = item.get("id")
        text = (item.get("chunk") or "").strip()
        if not text:
            continue

        source = _metadata_value(item, "source", "source_name", default=source_name)
        source_url = _metadata_value(item, "source_url", "url")
        title = _metadata_value(item, "title")

        metadata = {
            "chunk_id": str(chunk_id) if chunk_id is not None else None,
            "source": source,
            "source_name": source_name,
            "source_url": source_url,
            "source_file": item.get("source_file"),
            "title": title,
            "pub_year": item.get("pub_year"),
            "record_index": item.get("record_index"),
            "crawl_time": item.get("crawl_time"),
            "journal": item.get("journal"),
            "pmcid": item.get("pmcid"),
            "doi": item.get("doi"),
        }
        metadata = {k: v for k, v in metadata.items() if v not in (None, "")}

        docs.append(Document(page_content=text, metadata=metadata))
    return docs


def _build_embeddings() -> DashScopeEmbeddings:
    if not os.getenv("DASHSCOPE_API_KEY"):
        raise EnvironmentError("请先设置环境变量 DASHSCOPE_API_KEY")
    return DashScopeEmbeddings(model=DEFAULT_EMBEDDING_MODEL)


def embed_to_faiss(
    chunk_json_path: str,
    index_dir: str,
    source_name: str | None = None,
    batch_size: int = 64,
) -> Path:
    """
    读取 chunk JSON -> embedding -> 写入 FAISS，并保存到 index_dir。
    """
    json_path = Path(chunk_json_path).expanduser().resolve()
    out_dir = Path(index_dir).expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    if not json_path.exists():
        raise FileNotFoundError(f"找不到 chunk JSON：{json_path}")

    source_name = source_name or json_path.stem

    chunks = load_chunks(json_path)
    print(f"[INFO] Loaded chunks: {len(chunks)} from {json_path}")
    docs = build_documents(chunks, source_name=source_name)
    if not docs:
        raise ValueError(f"没有可写入向量库的有效 chunk：{json_path}")

    embeddings = _build_embeddings()

    vectorstore = None
    for i in range(0, len(docs), batch_size):
        batch_docs = docs[i : i + batch_size]
        if vectorstore is None:
            vectorstore = FAISS.from_documents(batch_docs, embeddings)
        else:
            vectorstore.add_documents(batch_docs)

        print(f"[INFO] Embedded {min(i + batch_size, len(docs))}/{len(docs)}")

    vectorstore.save_local(str(out_dir))
    print(f"[DONE] FAISS index saved to: {out_dir}")
    return out_dir


def demo_search(index_dir: str, query: str, top_k: int = 3) -> None:
    """
    演示：加载已保存的 FAISS 索引并做相似度检索。
    """
    embeddings = _build_embeddings()
    vs = FAISS.load_local(
        index_dir,
        embeddings,
        allow_dangerous_deserialization=True,
    )

    results = vs.similarity_search(query, k=top_k)
    print(f"\n[QUERY] {query}\n")
    for idx, doc in enumerate(results, 1):
        print(f"--- Top {idx} ---")
        print("metadata:", doc.metadata)
        print(doc.page_content[:300], "...\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="医疗知识库向量化与检索工具")
    subparsers = parser.add_subparsers(dest="command", required=True)

    build_parser = subparsers.add_parser("build", help="把 chunk JSON 写入 FAISS 索引")
    build_parser.add_argument("--input", required=True, help="chunk JSON 路径")
    build_parser.add_argument("--index-dir", required=True, help="FAISS 输出目录")
    build_parser.add_argument("--source-name", default=None, help="可选来源名，默认使用文件名")
    build_parser.add_argument("--batch-size", type=int, default=64, help="embedding 批大小")

    search_parser = subparsers.add_parser("search", help="在已有 FAISS 索引上做简单检索")
    search_parser.add_argument("--index-dir", required=True, help="FAISS 索引目录")
    search_parser.add_argument("--query", required=True, help="查询语句")
    search_parser.add_argument("--top-k", type=int, default=3, help="返回条数")

    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.command == "build":
        embed_to_faiss(
            chunk_json_path=args.input,
            index_dir=args.index_dir,
            source_name=args.source_name,
            batch_size=args.batch_size,
        )
        return

    if args.command == "search":
        demo_search(index_dir=args.index_dir, query=args.query, top_k=args.top_k)


if __name__ == "__main__":
    main()
