from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any, Dict, List


def _load_report(path: Path) -> Dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"报告格式错误: {path}")
    return data


def _flatten(report: Dict[str, Any], name: str) -> Dict[str, Any]:
    meta = report.get("meta", {})
    agg = report.get("aggregate", {})
    row: Dict[str, Any] = {
        "run_name": name,
        "index_dir": meta.get("index_dir"),
        "data": meta.get("data"),
        "retrieval_mode": meta.get("retrieval_mode"),
        "rerank": meta.get("rerank"),
        "cross_encoder_model": meta.get("cross_encoder_model"),
        "num_queries": agg.get("num_queries"),
        "elapsed_sec": agg.get("elapsed_sec"),
    }
    for k in agg.get("ks", []):
        row[f"recall@{k}"] = agg.get(f"recall@{k}")
        row[f"mrr@{k}"] = agg.get(f"mrr@{k}")
        row[f"hit@{k}"] = agg.get(f"hit@{k}")
    return row


def _write_csv(rows: List[Dict[str, Any]], path: Path) -> None:
    if not rows:
        return
    keys = []
    for row in rows:
        for key in row.keys():
            if key not in keys:
                keys.append(key)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=keys)
        writer.writeheader()
        writer.writerows(rows)


def _write_markdown(rows: List[Dict[str, Any]], path: Path) -> None:
    if not rows:
        return
    keys = []
    for row in rows:
        for key in row.keys():
            if key not in keys:
                keys.append(key)
    lines = []
    lines.append("| " + " | ".join(keys) + " |")
    lines.append("| " + " | ".join(["---"] * len(keys)) + " |")
    for row in rows:
        vals = [str(row.get(k, "")) for k in keys]
        lines.append("| " + " | ".join(vals) + " |")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="汇总多个评测报告为 CSV/Markdown")
    parser.add_argument(
        "--inputs",
        required=True,
        nargs="+",
        help="评测报告 JSON 路径列表",
    )
    parser.add_argument("--csv-output", required=True, help="CSV 输出路径")
    parser.add_argument("--md-output", required=True, help="Markdown 输出路径")
    parser.add_argument("--run-names", nargs="*", help="可选：每个输入对应的名称")
    args = parser.parse_args()

    if args.run_names and len(args.run_names) != len(args.inputs):
        raise SystemExit("run-names 数量需与 inputs 相同")

    rows: List[Dict[str, Any]] = []
    for idx, input_path in enumerate(args.inputs):
        path = Path(input_path)
        report = _load_report(path)
        name = args.run_names[idx] if args.run_names else path.stem
        rows.append(_flatten(report, name))

    _write_csv(rows, Path(args.csv_output))
    _write_markdown(rows, Path(args.md_output))
    print(f"[DONE] saved: {args.csv_output}, {args.md_output}")


if __name__ == "__main__":
    main()
