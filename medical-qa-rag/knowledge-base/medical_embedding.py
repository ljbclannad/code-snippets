
import os
import json
from pathlib import Path
from typing import List, Dict, Any

from langchain_core.documents import Document
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.vectorstores import FAISS


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

    # 过滤掉空 chunk
    cleaned = []
    for item in data:
        if not isinstance(item, dict):
            continue
        chunk = (item.get("chunk") or "").strip()
        if chunk:
            cleaned.append(item)
    return cleaned


def build_documents(chunks: List[Dict[str, Any]], source_name: str) -> List[Document]:
    """
    把 chunk 转成 LangChain Document，方便后续存入向量库。
    """
    docs: List[Document] = []
    for item in chunks:
        chunk_id = item.get("id")
        text = (item.get("chunk") or "").strip()
        region = item.get("region")
        effective_start = item.get("effective_start")
        effective_end = item.get("effective_end")

        """
        metadata：你将来检索到这个 chunk 时，能知道它来自哪个文件、chunk_id 是多少、甚至还能加上地区/版本/日期等用于过滤
        """
        docs.append(
            Document(
                page_content=text,
                metadata={
                    "chunk_id": chunk_id,
                    "source": source_name,  # 比如文件名、URL、政策编号等
                    "region": region,
                    "effective_start": effective_start,
                    "effective_end": effective_end,
                },
            )
        )
    return docs


def embed_to_faiss(
    chunk_json_path: str,
    index_dir: str,
    source_name: str | None = None,
    batch_size: int = 64,
) -> None:
    """
    读取 chunk JSON -> embedding -> 写入 FAISS，并保存到 index_dir。
    """
    json_path = Path(chunk_json_path).expanduser().resolve()
    out_dir = Path(index_dir).expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    if not json_path.exists():
        raise FileNotFoundError(f"找不到 chunk JSON：{json_path}")

    source_name = source_name or json_path.name

    # 1) 加载 chunks
    chunks = load_chunks(json_path)
    print(f"[INFO] Loaded chunks: {len(chunks)} from {json_path}")

    # 2) 构建 Documents
    docs = build_documents(chunks, source_name=source_name)

    # 3) 初始化 Embeddings
    if not os.getenv("DASHSCOPE_API_KEY"):
        raise EnvironmentError("请先设置环境变量 DASHSCOPE_API_KEY")

    embeddings = DashScopeEmbeddings(model="text-embedding-v4")

    # 4) 建库：分批写入，避免一次性太大/更稳
    vectorstore = None
    for i in range(0, len(docs), batch_size):
        batch_docs = docs[i : i + batch_size]
        if vectorstore is None:
            vectorstore = FAISS.from_documents(batch_docs, embeddings)
        else:
            vectorstore.add_documents(batch_docs)

        print(f"[INFO] Embedded {min(i + batch_size, len(docs))}/{len(docs)}")

    # 5) 持久化保存（会生成 index.faiss + index.pkl）
    vectorstore.save_local(str(out_dir))
    print(f"[DONE] FAISS index saved to: {out_dir}")


def demo_search(index_dir: str, query: str, top_k: int = 3) -> None:
    """
    演示：加载已保存的 FAISS 索引并做相似度检索。
    """
    if not os.getenv("DASHSCOPE_API_KEY"):
        raise EnvironmentError("请先设置环境变量 DASHSCOPE_API_KEY")

    embeddings = DashScopeEmbeddings(model="text-embedding-v4")
    vs = FAISS.load_local(
        index_dir,
        embeddings,
        allow_dangerous_deserialization=True,  # 本地可信场景可用
    )

    results = vs.similarity_search(query, k=top_k)
    print(f"\n[QUERY] {query}\n")
    for idx, doc in enumerate(results, 1):
        print(f"--- Top {idx} ---")
        print("metadata:", doc.metadata)
        print(doc.page_content[:300], "...\n")


if __name__ == "__main__":
    # 你生成的分块 JSON 文件路径（改成你的实际路径）
    CHUNK_JSON = "/Users/lejinbo/LLM/code-snippets/medical_docs/medical_documents_20251217_104142_cleaned_chunks.json"

    # 向量库输出目录（会写 index.faiss / index.pkl）
    INDEX_DIR = "/Users/lejinbo/LLM/code-snippets/medical_docs/medical_documents_20251217_104142_cleaned_faiss_index"

    # 1) 向量化并保存
    embed_to_faiss(
        chunk_json_path=CHUNK_JSON,
        index_dir=INDEX_DIR,
        source_name="医保政策-药品目录调整通知",  # 可选：方便溯源
        batch_size=64,
    )

    # 2) 简单检索验证
    demo_search(
        index_dir=INDEX_DIR,
        query="新增药品挂网工作需要在什么时候之前完成？",
        top_k=3,
    )
