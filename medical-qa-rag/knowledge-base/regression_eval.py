from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Dict, List, Sequence

from eval_retrieval import evaluate_retrieval
from report_eval import flatten_report, write_csv, write_markdown

DEFAULT_COMPARE_METRICS = ["recall@1", "recall@3", "mrr@3", "hit@3"]


def load_json(path: Path) -> Dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"配置文件必须是 JSON 对象：{path}")
    return data


def normalize_ks(value: Sequence[int] | str) -> List[int]:
    if isinstance(value, str):
        ks = [int(part.strip()) for part in value.split(",") if part.strip()]
    else:
        ks = [int(part) for part in value]
    ks = sorted(set(ks))
    if not ks:
        raise ValueError("ks 不能为空")
    return ks


def _run_defaults(config: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "ks": normalize_ks(config.get("ks", [1, 3, 5, 10])),
        "retrieval_mode": config.get("retrieval_mode", "dense"),
        "hybrid_method": config.get("hybrid_method", "rrf"),
        "dense_weight": float(config.get("dense_weight", 0.6)),
        "bm25_weight": float(config.get("bm25_weight", 0.4)),
        "rrf_k": int(config.get("rrf_k", 60)),
        "candidate_scale": int(config.get("candidate_scale", 2)),
        "rerank": config.get("rerank", "none"),
        "rerank_top_k": config.get("rerank_top_k"),
        "cross_encoder_model": config.get("cross_encoder_model", "BAAI/bge-reranker-base"),
        "cross_encoder_batch_size": int(config.get("cross_encoder_batch_size", 16)),
        "llm_rerank_batch_size": int(config.get("llm_rerank_batch_size", 5)),
        "llm_rerank_max_chars": int(config.get("llm_rerank_max_chars", 800)),
        "llm_rerank_blend_weight": float(config.get("llm_rerank_blend_weight", 0.85)),
    }


def run_matrix(config: Dict[str, Any], output_dir: Path) -> List[Dict[str, Any]]:
    if "index_dir" not in config or "data" not in config:
        raise ValueError("回归配置至少需要 index_dir 和 data")

    defaults = _run_defaults(config)
    runs = config.get("runs") or [{"name": "default"}]
    reports_dir = output_dir / "reports"
    reports_dir.mkdir(parents=True, exist_ok=True)

    rows: List[Dict[str, Any]] = []
    for idx, run in enumerate(runs):
        if not isinstance(run, dict):
            raise ValueError(f"runs[{idx}] 不是对象")
        run_name = run.get("name") or f"run_{idx + 1}"
        run_config = {**defaults, **{k: v for k, v in run.items() if k != "name"}}
        report = evaluate_retrieval(
            index_dir=str(config["index_dir"]),
            data=str(config["data"]),
            ks=normalize_ks(run_config["ks"]),
            retrieval_mode=run_config["retrieval_mode"],
            hybrid_method=run_config["hybrid_method"],
            dense_weight=float(run_config["dense_weight"]),
            bm25_weight=float(run_config["bm25_weight"]),
            rrf_k=int(run_config["rrf_k"]),
            candidate_scale=int(run_config["candidate_scale"]),
            rerank=str(run_config["rerank"]),
            rerank_top_k=run_config.get("rerank_top_k"),
            cross_encoder_model=str(run_config["cross_encoder_model"]),
            cross_encoder_batch_size=int(run_config["cross_encoder_batch_size"]),
            llm_rerank_batch_size=int(run_config["llm_rerank_batch_size"]),
            llm_rerank_max_chars=int(run_config["llm_rerank_max_chars"]),
            llm_rerank_blend_weight=float(run_config["llm_rerank_blend_weight"]),
        )
        report_path = reports_dir / f"{run_name}.json"
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        row = flatten_report(report, run_name)
        rows.append(row)

    return rows


def build_baseline_payload(
    rows: Sequence[Dict[str, Any]],
    compare_metrics: Sequence[str],
    allowed_drop: float,
    config: Dict[str, Any],
) -> Dict[str, Any]:
    baseline_runs: Dict[str, Dict[str, float]] = {}
    for row in rows:
        run_name = str(row["run_name"])
        baseline_runs[run_name] = {}
        for metric in compare_metrics:
            value = row.get(metric)
            if value is None:
                continue
            baseline_runs[run_name][metric] = float(value)

    return {
        "meta": {
            "data": config.get("data"),
            "index_dir": config.get("index_dir"),
            "ks": normalize_ks(config.get("ks", [1, 3, 5, 10])),
            "compare_metrics": list(compare_metrics),
            "allowed_drop": float(allowed_drop),
        },
        "runs": baseline_runs,
    }


def compare_against_baseline(
    rows: Sequence[Dict[str, Any]],
    baseline: Dict[str, Any],
    compare_metrics: Sequence[str],
    allowed_drop: float,
) -> List[Dict[str, Any]]:
    current_by_run = {str(row["run_name"]): row for row in rows}
    baseline_runs = baseline.get("runs", {})
    regressions: List[Dict[str, Any]] = []

    for run_name, expected_metrics in baseline_runs.items():
        current_row = current_by_run.get(run_name)
        if current_row is None:
            regressions.append(
                {
                    "run_name": run_name,
                    "metric": "*",
                    "baseline": None,
                    "current": None,
                    "delta": None,
                    "message": "当前结果缺少该 run",
                }
            )
            continue

        for metric in compare_metrics:
            if metric not in expected_metrics:
                continue
            baseline_value = float(expected_metrics[metric])
            current_value = current_row.get(metric)
            if current_value is None:
                regressions.append(
                    {
                        "run_name": run_name,
                        "metric": metric,
                        "baseline": baseline_value,
                        "current": None,
                        "delta": None,
                        "message": "当前结果缺少该指标",
                    }
                )
                continue

            current_value = float(current_value)
            delta = current_value - baseline_value
            if current_value + allowed_drop < baseline_value:
                regressions.append(
                    {
                        "run_name": run_name,
                        "metric": metric,
                        "baseline": baseline_value,
                        "current": current_value,
                        "delta": delta,
                        "message": f"指标回退超过阈值 {allowed_drop}",
                    }
                )

    return regressions


def write_regression_markdown(
    rows: Sequence[Dict[str, Any]],
    regressions: Sequence[Dict[str, Any]],
    compare_metrics: Sequence[str],
    output_path: Path,
) -> None:
    lines: List[str] = []
    lines.append("# Regression Summary")
    lines.append("")
    lines.append(f"- runs: {len(rows)}")
    lines.append(f"- compare_metrics: {', '.join(compare_metrics)}")
    lines.append(f"- regressions: {len(regressions)}")
    lines.append("")

    if regressions:
        lines.append("## Regressions")
        lines.append("")
        for item in regressions:
            lines.append(
                f"- {item['run_name']} / {item['metric']}: baseline={item['baseline']} current={item['current']} delta={item['delta']} ({item['message']})"
            )
        lines.append("")

    lines.append("## Current Runs")
    lines.append("")
    keys: List[str] = []
    for row in rows:
        for key in row.keys():
            if key not in keys:
                keys.append(key)
    lines.append("| " + " | ".join(keys) + " |")
    lines.append("| " + " | ".join(["---"] * len(keys)) + " |")
    for row in rows:
        lines.append("| " + " | ".join(str(row.get(key, "")) for key in keys) + " |")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines), encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="批量评测并与回归基线比较")
    parser.add_argument("--config", required=True, help="回归配置 JSON 路径")
    parser.add_argument("--output-dir", required=True, help="输出目录")
    parser.add_argument("--baseline", help="已有基线 JSON 路径")
    parser.add_argument("--write-baseline", help="把本次结果写成新的基线 JSON")
    parser.add_argument("--allowed-drop", type=float, default=None, help="允许回退阈值，默认取配置或基线中的值")
    parser.add_argument("--fail-on-regression", action="store_true", help="有回退时返回非零退出码")
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    config_path = Path(args.config).expanduser().resolve()
    output_dir = Path(args.output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    config = load_json(config_path)
    rows = run_matrix(config, output_dir)

    compare_metrics = list(config.get("compare_metrics", DEFAULT_COMPARE_METRICS))
    allowed_drop = (
        float(args.allowed_drop)
        if args.allowed_drop is not None
        else float(config.get("allowed_drop", 0.0))
    )

    write_csv(rows, output_dir / "summary.csv")
    write_markdown(rows, output_dir / "summary.md")

    regressions: List[Dict[str, Any]] = []
    baseline_payload = None
    if args.baseline:
        baseline_path = Path(args.baseline).expanduser().resolve()
        baseline_payload = load_json(baseline_path)
        allowed_drop = (
            float(args.allowed_drop)
            if args.allowed_drop is not None
            else float(baseline_payload.get("meta", {}).get("allowed_drop", allowed_drop))
        )
        regressions = compare_against_baseline(rows, baseline_payload, compare_metrics, allowed_drop)

    result_payload = {
        "config": str(config_path),
        "output_dir": str(output_dir),
        "compare_metrics": compare_metrics,
        "allowed_drop": allowed_drop,
        "regressions": regressions,
        "num_runs": len(rows),
    }
    (output_dir / "regression_result.json").write_text(
        json.dumps(result_payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    write_regression_markdown(rows, regressions, compare_metrics, output_dir / "regression_result.md")

    if args.write_baseline:
        baseline_output = Path(args.write_baseline).expanduser().resolve()
        baseline_output.parent.mkdir(parents=True, exist_ok=True)
        baseline_payload = build_baseline_payload(rows, compare_metrics, allowed_drop, config)
        baseline_output.write_text(json.dumps(baseline_payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[DONE] baseline saved: {baseline_output}")

    print(f"[DONE] regression summary saved to: {output_dir}")
    if regressions:
        print(f"[WARN] regressions detected: {len(regressions)}")
        if args.fail_on_regression:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
