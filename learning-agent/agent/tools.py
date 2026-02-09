import ast
import operator
from pathlib import Path
from typing import Any, Callable, Dict, List


BASE_DIR = Path(__file__).resolve().parents[1]
KB_DIR = BASE_DIR / "knowledge-base"
NOTES_PATH = KB_DIR / "notes.jsonl"


class ToolError(Exception):
    pass


def _safe_eval(expr: str) -> float:
    allowed_ops = {
        ast.Add: operator.add,
        ast.Sub: operator.sub,
        ast.Mult: operator.mul,
        ast.Div: operator.truediv,
        ast.Pow: operator.pow,
        ast.Mod: operator.mod,
    }

    def _eval(node: ast.AST) -> float:
        if isinstance(node, ast.Expression):
            return _eval(node.body)
        if isinstance(node, ast.Num):  # py<3.8
            return float(node.n)
        if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
            return float(node.value)
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            value = _eval(node.operand)
            return value if isinstance(node.op, ast.UAdd) else -value
        if isinstance(node, ast.BinOp) and type(node.op) in allowed_ops:
            left = _eval(node.left)
            right = _eval(node.right)
            return allowed_ops[type(node.op)](left, right)
        raise ToolError("Unsupported expression.")

    parsed = ast.parse(expr, mode="eval")
    return _eval(parsed)


def tool_calc(args: Dict[str, Any]) -> str:
    expr = args.get("expr", "").strip()
    if not expr:
        raise ToolError("Missing expr.")
    value = _safe_eval(expr)
    return f"{expr} = {value}"


def tool_note_stats(args: Dict[str, Any]) -> str:
    if not NOTES_PATH.exists():
        return "No notes yet."
    count = 0
    tag_counts: Dict[str, int] = {}
    with open(NOTES_PATH, "r", encoding="utf-8") as file:
        for line in file:
            line = line.strip()
            if not line:
                continue
            count += 1
            if "\"tags\"" in line:
                # light parsing to keep it simple
                try:
                    import json

                    tags = json.loads(line).get("tags", [])
                    for tag in tags:
                        tag_counts[tag] = tag_counts.get(tag, 0) + 1
                except Exception:
                    continue
    if not count:
        return "No notes yet."
    top_tags = sorted(tag_counts.items(), key=lambda item: item[1], reverse=True)[:5]
    tag_info = ", ".join(f"{tag}:{num}" for tag, num in top_tags) if top_tags else "n/a"
    return f"Notes: {count}. Top tags: {tag_info}"


def get_tools() -> List[Dict[str, Any]]:
    return [
        {
            "name": "calc",
            "description": "Evaluate a simple math expression",
            "args": {"expr": "string"},
            "handler": tool_calc,
        },
        {
            "name": "note_stats",
            "description": "Show note count and top tags",
            "args": {},
            "handler": tool_note_stats,
        },
    ]


def list_tools() -> List[Dict[str, Any]]:
    return [{"name": tool["name"], "description": tool["description"], "args": tool["args"]} for tool in get_tools()]


def run_tool(name: str, args: Dict[str, Any]) -> str:
    for tool in get_tools():
        if tool["name"] == name:
            handler: Callable[[Dict[str, Any]], str] = tool["handler"]
            return handler(args)
    raise ToolError(f"Unknown tool: {name}")
