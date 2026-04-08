from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent
KNOWLEDGE_BASE_DIR = REPO_ROOT / "knowledge-base"
CRAWLER_DIR = REPO_ROOT / "crawler"

for path in (str(KNOWLEDGE_BASE_DIR), str(CRAWLER_DIR)):
    if path not in sys.path:
        sys.path.insert(0, path)

from MedicalCrawler import MedicalDocumentCrawler
from cleaning import clean_json_file, clean_text_file
from medical_chunk import (
    chunk_fixed,
    chunk_json_with_metadata,
    chunk_semantic,
    chunk_structured,
    load_text,
    save_chunks,
)


def _clean_file(input_path: Path, output_dir: Path) -> Path:
    if input_path.suffix.lower() == ".json":
        return clean_json_file(input_path, output_dir)
    if input_path.suffix.lower() == ".txt":
        return clean_text_file(input_path, output_dir)
    raise ValueError(f"暂不支持的输入格式：{input_path.suffix}")


def _chunk_file(
    input_path: Path,
    strategy: str,
    max_chars: int,
    min_chars: int | None,
    overlap: int,
    overlap_sentences: int,
    output_path: Path,
) -> Path:
    if strategy == "semantic_meta":
        if input_path.suffix.lower() != ".json":
            raise ValueError("semantic_meta 仅支持 JSON 输入")
        chunks = chunk_json_with_metadata(
            input_path,
            max_chars=max_chars,
            min_chars=min_chars,
            overlap_sentences=overlap_sentences,
        )
    else:
        text = load_text(input_path)
        if strategy == "fixed":
            chunks = chunk_fixed(text, max_chars=max_chars, overlap=overlap)
        elif strategy == "structured":
            chunks = chunk_structured(text, max_chars=max_chars)
        elif strategy == "semantic":
            chunks = chunk_semantic(
                text,
                max_chars=max_chars,
                min_chars=min_chars,
                overlap_sentences=overlap_sentences,
            )
        else:
            raise ValueError(f"不支持的切分策略：{strategy}")

    save_chunks(chunks, output_path)
    return output_path


def handle_crawl(args: argparse.Namespace) -> None:
    crawler = MedicalDocumentCrawler(output_dir=args.output_dir)
    documents = crawler.crawl_all(target_count=args.target_count)
    if not documents:
        raise SystemExit("未获取到任何文档")

    json_path = crawler.save_documents(filename=args.json_filename)
    text_path = crawler.save_documents_text(filename=args.text_filename)
    crawler.print_statistics()
    print(json.dumps({"json": json_path, "text": text_path, "count": len(documents)}, ensure_ascii=False, indent=2))


def handle_build_index(args: argparse.Namespace) -> None:
    from medical_embedding import embed_to_faiss

    input_path = Path(args.input).expanduser().resolve()
    if not input_path.exists():
        raise SystemExit(f"输入文件不存在：{input_path}")

    clean_output_dir = Path(args.clean_output_dir).expanduser().resolve()
    clean_output_dir.mkdir(parents=True, exist_ok=True)
    cleaned_path = _clean_file(input_path, clean_output_dir)

    chunks_output = (
        Path(args.chunks_output).expanduser().resolve()
        if args.chunks_output
        else cleaned_path.with_name(f"{cleaned_path.stem}_chunks.json")
    )
    chunked_path = _chunk_file(
        cleaned_path,
        strategy=args.strategy,
        max_chars=args.max_chars,
        min_chars=args.min_chars,
        overlap=args.overlap,
        overlap_sentences=args.overlap_sentences,
        output_path=chunks_output,
    )

    index_dir = (
        Path(args.index_dir).expanduser().resolve()
        if args.index_dir
        else chunked_path.with_name(f"{chunked_path.stem}_faiss_index")
    )
    embed_to_faiss(
        chunk_json_path=str(chunked_path),
        index_dir=str(index_dir),
        source_name=args.source_name,
        batch_size=args.batch_size,
    )
    print(
        json.dumps(
            {
                "cleaned": str(cleaned_path),
                "chunks": str(chunked_path),
                "index_dir": str(index_dir),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


def handle_answer(args: argparse.Namespace) -> None:
    from medical_model_chat import load_faiss, rag_qa

    vs = load_faiss(args.index_dir)
    result = rag_qa(
        vs,
        args.question,
        k=args.top_k,
        retrieval_mode=args.retrieval_mode,
        structured_output=args.structured_output,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="医疗 QA RAG 统一流水线入口")
    subparsers = parser.add_subparsers(dest="command", required=True)

    crawl_parser = subparsers.add_parser("crawl", help="抓取医疗文档")
    crawl_parser.add_argument("--target-count", type=int, default=50, help="目标文档数")
    crawl_parser.add_argument("--output-dir", default="medical_docs", help="输出目录")
    crawl_parser.add_argument("--json-filename", default=None, help="可选 JSON 文件名")
    crawl_parser.add_argument("--text-filename", default=None, help="可选文本文件名")
    crawl_parser.set_defaults(handler=handle_crawl)

    build_parser = subparsers.add_parser("build-index", help="清洗、切分并构建 FAISS 索引")
    build_parser.add_argument("--input", required=True, help="原始 txt/json 文件")
    build_parser.add_argument("--clean-output-dir", default="medical_docs", help="清洗后输出目录")
    build_parser.add_argument("--chunks-output", default=None, help="chunk JSON 输出路径")
    build_parser.add_argument("--index-dir", default=None, help="FAISS 索引输出目录")
    build_parser.add_argument(
        "--strategy",
        choices=["fixed", "structured", "semantic", "semantic_meta"],
        default="semantic_meta",
        help="切分策略",
    )
    build_parser.add_argument("--max-chars", type=int, default=500, help="chunk 最大字符数")
    build_parser.add_argument("--min-chars", type=int, default=None, help="chunk 最小字符数")
    build_parser.add_argument("--overlap", type=int, default=80, help="fixed 策略的字符重叠")
    build_parser.add_argument("--overlap-sentences", type=int, default=1, help="semantic 策略的句子重叠")
    build_parser.add_argument("--batch-size", type=int, default=64, help="embedding 批大小")
    build_parser.add_argument("--source-name", default=None, help="可选来源名")
    build_parser.set_defaults(handler=handle_build_index)

    answer_parser = subparsers.add_parser("answer", help="在已有索引上执行问答")
    answer_parser.add_argument("--index-dir", required=True, help="FAISS 索引目录")
    answer_parser.add_argument("--question", required=True, help="提问内容")
    answer_parser.add_argument("--top-k", type=int, default=5, help="检索条数")
    answer_parser.add_argument(
        "--retrieval-mode",
        default="dense",
        choices=["dense", "bm25", "hybrid", "hybrid_rrf", "hybrid_weighted"],
        help="检索模式",
    )
    answer_parser.add_argument("--structured-output", action="store_true", help="返回结构化 JSON")
    answer_parser.set_defaults(handler=handle_answer)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
