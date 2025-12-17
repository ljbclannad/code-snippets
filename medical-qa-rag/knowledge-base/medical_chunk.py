"""
RAG 切分脚本
------------
针对已清洗文本，提供三种经典切分方式：
1) 固定长度切分：按字符长度 + 重叠
2) 结构化切分：基于常见标题/序号行分段
3) 语义切分：按句子组合到目标长度

默认读取 `medical_docs` 目录下的 *_cleaned.txt / *_cleaned.json，
输出到 *_chunks.json，便于后续向量化。
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Dict, Iterable, List, Sequence

DEFAULT_MAX_CHARS = 500
DEFAULT_OVERLAP = 80


# ----------------------------
# 通用加载与保存
# ----------------------------
def load_text(path: Path) -> str:
    """加载文本或 JSON，优先使用 cleaned_content。"""
    if path.suffix.lower() == ".txt":
        return path.read_text(encoding="utf-8", errors="ignore")

    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, list):
        parts: List[str] = []
        for item in data:
            if not isinstance(item, dict):
                continue
            if item.get("cleaned_content"):
                parts.append(str(item["cleaned_content"]))
            elif item.get("content"):
                parts.append(str(item["content"]))
        return "\n\n".join(parts)
    if isinstance(data, dict):
        if data.get("cleaned_content"):
            return str(data["cleaned_content"])
        if data.get("content"):
            return str(data["content"])
    raise ValueError("JSON 文件缺少 content/cleaned_content 字段")


def save_chunks(chunks: Sequence[str], output_path: Path) -> None:
    payload = [{"id": idx, "chunk": chunk} for idx, chunk in enumerate(chunks)]
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


# ----------------------------
# 切分策略
# ----------------------------
def chunk_fixed(text: str, max_chars: int = DEFAULT_MAX_CHARS, overlap: int = DEFAULT_OVERLAP) -> List[str]:
    """固定长度切分，带重叠。"""
    max_chars = max(max_chars, 1)
    # 重叠字符数不能大于最大字符数-1
    overlap = max(0, min(overlap, max_chars - 1))
    step = max_chars - overlap

    chunks: List[str] = []
    start = 0
    length = len(text)
    while start < length:
        end = min(start + max_chars, length)
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        start += step
    return chunks


HEADING_PATTERNS = [
    re.compile(r"^\s*第[一二三四五六七八九十百千]+[章节部分篇]"),  # 第三章
    re.compile(r"^\s*[一二三四五六七八九十百]+[、\.．]"),  # 一、 二.
    re.compile(r"^\s*（[一二三四五六七八九十]+）"),  # （一）
    re.compile(r"^\s*\d+[\.．、]"),  # 1. 2、
]


def is_heading(line: str) -> bool:
    return any(pat.match(line) for pat in HEADING_PATTERNS) and len(line) <= 60


def chunk_structured(text: str, max_chars: int = DEFAULT_MAX_CHARS) -> List[str]:
    """
    按标题/序号行分段；若段落仍过长，再按固定长度切。
    """
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    sections: List[str] = []

    current: List[str] = []
    for line in lines:
        if is_heading(line) and current:
            sections.append("\n".join(current).strip())
            current = [line]
        else:
            current.append(line)
    # 将当前段落添加到sections中，最后段落会在current.append(line)中结束循环，需要补充添加一次
    if current:
        sections.append("\n".join(current).strip())

    # 对过长段落做二次切分
    final_chunks: List[str] = []
    for sec in sections:
        if len(sec) <= max_chars:
            final_chunks.append(sec)
        else:
            final_chunks.extend(chunk_fixed(sec, max_chars=max_chars, overlap=max_chars // 5))
    return final_chunks


SENTENCE_SPLIT_RE = re.compile(r"(?<=[。！？!?])")


def split_sentences(text: str) -> List[str]:
    parts = [p.strip() for p in SENTENCE_SPLIT_RE.split(text) if p.strip()]
    merged: List[str] = []
    """
        如果 part 长度 ≤ 2（比如“嗯”“好”“。”这类短碎片，或者某些文本清洗后产生的残渣）
        并且 merged 里已经有上一句
        那就把这个超短 part 拼回上一句末尾，避免出现很多没意义的“超短句子”影响 chunk 质量。
    """
    for part in parts:
        if merged and len(part) <= 2:
            merged[-1] = f"{merged[-1]}{part}"
        else:
            merged.append(part)
    return merged


# 示例：按句子拼 chunk（chunk_semantic 的核心流程演示）
# 设定：
#   max_chars = 10        # 每个 chunk 最大字符数
#   min_chars = 5         # 当前 buf 达到这个长度后，才允许切块
#   overlap_sentences = 1 # 相邻 chunk 之间重叠 1 句（保留上一块的尾句）
#
# 假设 split_sentences 得到 4 个句子，且每句长度都为 4：
#   S1(len=4), S2(len=4), S3(len=4), S4(len=4)
#
# 初始化：
#   buf = []      # 当前缓冲区（尚未输出的句子列表）
#   buf_len = 0   # buf 中所有句子的总字符数
#   chunks = []   # 输出结果
#
# 迭代过程（for sent in sentences）：
#
# 1) sent = S1(4)
#    buf_len + 4 = 0 + 4 <= 10，不触发切块
#    buf = [S1], buf_len = 4
#
# 2) sent = S2(4)
#    buf_len + 4 = 4 + 4 <= 10，不触发切块
#    buf = [S1, S2], buf_len = 8
#
# 3) sent = S3(4)
#    buf_len + 4 = 8 + 4 = 12 > 10 且 buf_len(8) >= min_chars(5) -> 触发切块：
#      输出 chunk1 = "".join(buf) = S1+S2
#      chunks = [S1+S2]
#    处理重叠 overlap_sentences=1：
#      buf = buf[-1:] = [S2]               # 保留上一块的尾句
#      buf_len = len(S2) = 4               # 重新计算 buf_len（因为 buf 被截断了）
#    然后把 S3 加入 buf：
#      buf = [S2, S3], buf_len = 8
#
# 4) sent = S4(4)
#    buf_len + 4 = 8 + 4 = 12 > 10 且 buf_len(8) >= 5 -> 触发切块：
#      输出 chunk2 = S2+S3
#      chunks = [S1+S2, S2+S3]
#    重叠处理：
#      buf = [S3], buf_len = 4
#    加入 S4：
#      buf = [S3, S4], buf_len = 8
#
# 循环结束后收尾：
#   buf 非空 -> 输出最后一块 chunk3 = S3+S4
#   chunks = [S1+S2, S2+S3, S3+S4]
#
# 总结：
#   overlap_sentences=1 会让相邻 chunk 共享 1 句，提升上下文连续性（适合 RAG）。

def chunk_semantic(
    text: str,
    max_chars: int = DEFAULT_MAX_CHARS,
    min_chars: int | None = None,
    overlap_sentences: int = 1,
) -> List[str]:
    """
    句子级组合到目标长度，适合中文语义连续段落。
    overlap_sentences：在相邻块之间重复的尾句数量。
    """
    max_chars = max(max_chars, 80)
    min_chars = min_chars or max_chars // 2
    sentences = split_sentences(text)

    chunks: List[str] = []
    buf: List[str] = []
    buf_len = 0

    for sent in sentences:
        sent_len = len(sent)
        """
            如果当前句长度 + 缓冲区长度 > 最大字符数
            并且缓冲区长度 >= 最小字符数
            那就把缓冲区内容添加到chunks中
            并且处理重叠句
        """
        if buf_len + sent_len > max_chars and buf_len >= min_chars:
            chunks.append("".join(buf).strip())
            # 处理重叠句
            if overlap_sentences > 0:
                buf = buf[-overlap_sentences:]
                buf_len = sum(len(s) for s in buf)
            else:
                buf = []
                buf_len = 0
        buf.append(sent)
        buf_len += sent_len

    if buf:
        chunks.append("".join(buf).strip())
    return chunks


# ----------------------------
# CLI
# ----------------------------
def parse_args() -> argparse.Namespace:
    '''
        python medical_chunk.py --input ./medical_docs/medical_documents_20251217_104142_cleaned.txt 
        --strategy fixed
        --max-chars 500
        --overlap 80
        --output ./medical_docs/medical_documents_20251217_104142_cleaned_chunks.json
    '''
    parser = argparse.ArgumentParser(description="RAG 切分工具：fixed / structured / semantic")
    parser.add_argument(
        "--input",
        "-i",
        type=str,
        default="/Users/lejinbo/LLM/code-snippets/medical_docs/medical_documents_20251217_104142_cleaned.txt",
        help="待切分的文件路径，支持 .txt / .json（含 cleaned_content）。",
    )
    parser.add_argument(
        "--strategy",
        "-s",
        choices=["fixed", "structured", "semantic"],
        default="fixed",
        help="切分策略：fixed|structured|semantic",
    )
    parser.add_argument(
        "--max-chars", type=int, default=DEFAULT_MAX_CHARS, help="单块最大字符数（fixed/structured/semantic）"
    )
    parser.add_argument(
        "--overlap",
        type=int,
        default=DEFAULT_OVERLAP,
        help="fixed 重叠字符数；semantic 使用 overlap_sentences=1，可按需调整代码。",
    )
    parser.add_argument(
        "--output",
        "-o",
        type=str,
        default=None,
        help="输出路径（JSON）。默认与输入同目录，文件名增加 _chunks.json。",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    input_path = Path(args.input).expanduser()
    if not input_path.exists():
        raise SystemExit(f"输入文件不存在: {input_path}")

    text = load_text(input_path)

    if args.strategy == "fixed":
        chunks = chunk_fixed(text, max_chars=args.max_chars, overlap=args.overlap)
    elif args.strategy == "structured":
        chunks = chunk_structured(text, max_chars=args.max_chars)
    else:
        chunks = chunk_semantic(text, max_chars=args.max_chars, overlap_sentences=1)

    output_path = (
        Path(args.output).expanduser()
        if args.output
        else input_path.with_name(f"{input_path.stem}_chunks.json")
    )
    save_chunks(chunks, output_path)
    print(f"切分完成: {len(chunks)} 块 -> {output_path}")


if __name__ == "__main__":
    main()

