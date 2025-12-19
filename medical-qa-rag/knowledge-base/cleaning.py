"""
medical_docs 清洗脚本
--------------------
对 `medical_docs` 下的原始抓取文件进行轻量清洗，去掉重复口号、
联系方式、分隔符等噪声，生成带有 cleaned_content 的输出文件。
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Iterable, List, Dict, Any

# 行级别需要直接丢弃的噪声模式
NOISE_LINE_PATTERNS: List[re.Pattern[str]] = [
    re.compile(r"^={3,}$"),  # 分隔符
    re.compile(r"^文档\s*\d+[:：]?"),  # 文档编号
    re.compile(r"^来源[:：]"),  # 来源行
    re.compile(r"^链接[:：]"),  # 链接行
    re.compile(r"^抓取时间[:：]"),  # 抓取时间行
    re.compile(r"^附件[:：]"),  # 附件提示
    re.compile(r"^原标题[:：]"),
    re.compile(r"^文件下载链接[:：]"),
    re.compile(r"^点击查看详情"),
]

# 需要在文本中替换掉的噪声短语/正则
INLINE_NOISE_PATTERNS: List[re.Pattern[str]] = [
    # 免责声明
    re.compile(
        r"中国医保，一生守护！维护医保基金安全，人人有责！打击欺诈骗保举报电话：\d{3,4}-\d+，\d{3,4}-\d+；邮箱：\S+。?"
    ),
    re.compile(
        r"地址：\S+ 邮编：\d+ 电话：\d{3,4}-\d+ 中华人民共和国国家卫生健康委员会 版权所有，不得非法镜像． ICP备案编号：\S+ 技术支持：\S+"
    ),
    re.compile(
        r"地址：北京市西城区西直门外南路1号 邮编：100044 电话：010-68797979京公网安备 11010202000005号 ICP备案编号：京ICP备18052910号网站标识码：bm24000006 中华人民共和国国家卫生健康委员会 版权所有 技术支持：国家卫生健康委员会统计信息中心网站地图 版权所有：中华人民共和国国家卫生健康委员会"
    ),
    re.compile(r"文件下载链接[:：][^。；\n]*"),
    re.compile(r"邮箱：\S+"),
    re.compile(r"举报电话：\d{3,4}-\d+，\d{3,4}-\d+"),
    re.compile(r"原标题[:：]"),
]


def clean_lines(text: str) -> str:
    """逐行过滤 + 内嵌噪声清洗。"""
    cleaned: List[str] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if any(pattern.search(line) for pattern in NOISE_LINE_PATTERNS):
            continue

        for pattern in INLINE_NOISE_PATTERNS:
            line = pattern.sub(" ", line)

        # 压缩多余空白
        line = re.sub(r"\s{2,}", " ", line).strip()
        if line:
            cleaned.append(line)

    return "\n".join(cleaned)


def clean_text_file(path: Path, output_dir: Path) -> Path:
    output_path = output_dir / f"{path.stem}_cleaned{path.suffix}"
    text = path.read_text(encoding="utf-8", errors="ignore")
    cleaned = clean_lines(text)
    output_path.write_text(cleaned, encoding="utf-8")
    return output_path


def clean_json_file(path: Path, output_dir: Path) -> Path:
    output_path = output_dir / f"{path.stem}_cleaned{path.suffix}"
    data: Any = json.loads(path.read_text(encoding="utf-8"))

   
    if isinstance(data, list):
        cleaned_data: List[Dict[str, Any]] = []
        for item in data:
            if not isinstance(item, dict):
                continue
            new_item = dict(item)
            content = str(item.get("content", ""))
            new_item["cleaned_content"] = clean_lines(content)
            cleaned_data.append(new_item)
    elif isinstance(data, dict):
        cleaned_data = dict(data)
        content = str(data.get("content", ""))
        cleaned_data["cleaned_content"] = clean_lines(content)
    else:
        raise ValueError("JSON 文件格式未知，仅支持对象或对象列表。")

    output_path.write_text(
        json.dumps(cleaned_data, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return output_path


def iter_target_files(target: Path) -> Iterable[Path]:
    if target.is_file():
        yield target
        return
    for file in target.glob("*"):
        if file.suffix.lower() in {".txt", ".json"} and not file.stem.endswith("_cleaned"):
            yield file


def main() -> None:

    '''
        python cleaning.py --input ./medical_docs --output ./medical_docs_cleaned
    '''
    parser = argparse.ArgumentParser(
        description="清洗 medical_docs 的原始文本/JSON，去除常见噪声。"
    )
    parser.add_argument(
        "--input",
        "-i",
        type=str,
        default="/Users/lejinbo/LLM/code-snippets/medical_docs",
        help="待清洗的文件或目录路径（支持 .txt / .json）。",
    )
    parser.add_argument(
        "--output",
        "-o",
        type=str,
        default="/Users/lejinbo/LLM/code-snippets/medical_docs",
        help="输出目录，默认覆盖到 medical_docs 目录旁生成 *_cleaned 文件。",
    )
    args = parser.parse_args()

    input_path = Path(args.input).expanduser()
    output_dir = Path(args.output).expanduser()
    output_dir.mkdir(parents=True, exist_ok=True)

    processed: List[Path] = []
    for file_path in iter_target_files(input_path):
        if file_path.suffix.lower() == ".json":
            processed.append(clean_json_file(file_path, output_dir))
        elif file_path.suffix.lower() == ".txt":
            processed.append(clean_text_file(file_path, output_dir))

    if not processed:
        raise SystemExit("未找到可处理的 .txt 或 .json 文件")

    print("已完成清洗:")
    for p in processed:
        print(f"- {p}")


if __name__ == "__main__":
    main()
