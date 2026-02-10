import json
import sys
from pathlib import Path

from .utils import build_tfidf, load_notes


def build_index(notes_path: Path, index_path: Path) -> int:
    notes = load_notes(str(notes_path))
    if not notes:
        print("No notes found. Please add notes to notes.jsonl first.")
        return 1

    idf, vectors, norms, tokenized = build_tfidf(notes)
    docs = []
    for note, tokens, vector, norm in zip(notes, tokenized, vectors, norms):
        docs.append(
            {
                "id": note.get("id", ""),
                "text": note.get("text", ""),
                "tags": note.get("tags", []),
                "tokens": tokens,
                "vector": vector,
                "norm": norm,
            }
        )

    index = {"doc_count": len(notes), "idf": idf, "docs": docs}
    index_path.write_text(
        json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"Index saved to {index_path}")
    return 0


def main() -> int:
    base_dir = Path(__file__).resolve().parent
    notes_path = base_dir / "notes.jsonl"
    index_path = base_dir / "index.json"
    return build_index(notes_path, index_path)


if __name__ == "__main__":
    sys.exit(main())
