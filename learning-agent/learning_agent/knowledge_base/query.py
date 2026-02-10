import json
from pathlib import Path
from typing import List, Tuple

from .utils import build_query_vector, sparse_dot, tokenize, vector_norm


def load_index(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def search(index: dict, question: str, top_k: int = 3) -> List[Tuple[float, dict]]:
    tokens = tokenize(question)
    query_vec = build_query_vector(tokens, index.get("idf", {}))
    query_norm = vector_norm(query_vec)
    if query_norm == 0:
        return []

    results: List[Tuple[float, dict]] = []
    for doc in index.get("docs", []):
        doc_norm = doc.get("norm", 0.0)
        if doc_norm == 0:
            continue
        score = sparse_dot(query_vec, doc.get("vector", {})) / (query_norm * doc_norm)
        results.append((score, doc))

    results.sort(key=lambda item: item[0], reverse=True)
    return results[:top_k]


def main() -> None:
    base_dir = Path(__file__).resolve().parent
    index_path = base_dir / "index.json"
    if not index_path.exists():
        print("Index not found. Run build_index.py first.")
        return

    question = input("Question: ").strip()
    if not question:
        print("Empty question.")
        return

    index = load_index(index_path)
    results = search(index, question)
    if not results:
        print("No results.")
        return

    for score, doc in results:
        print(f"[{score:.3f}] {doc.get('id', '')} -> {doc.get('text', '')}")


if __name__ == "__main__":
    main()
