import ast
import json
import operator
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional


BASE_DIR = Path(__file__).resolve().parents[1]
KB_DIR = BASE_DIR / "knowledge_base"
NOTES_PATH = KB_DIR / "notes.jsonl"
MOCK_DATA_PATH = KB_DIR / "mock_data.json"


class ToolError(Exception):
    pass


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    parameters: Dict[str, Any]
    handler: Callable[[Dict[str, Any]], str]


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: Dict[str, ToolSpec] = {}

    def register(self, tool: ToolSpec) -> None:
        if tool.name in self._tools:
            raise ToolError(f"Duplicate tool: {tool.name}")
        self._tools[tool.name] = tool

    def list_schemas(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": tool.name,
                "description": tool.description,
                "parameters": tool.parameters,
            }
            for tool in self._tools.values()
        ]

    def get(self, name: str) -> Optional[ToolSpec]:
        return self._tools.get(name)

    def run(self, name: str, args: Dict[str, Any]) -> str:
        tool = self.get(name)
        if tool is None:
            raise ToolError(f"Unknown tool: {name}")
        _validate_args(tool.parameters, args)
        return tool.handler(args)


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


def _load_mock_data() -> Dict[str, List[Dict[str, Any]]]:
    if not MOCK_DATA_PATH.exists():
        raise ToolError("Mock data not found.")
    with open(MOCK_DATA_PATH, "r", encoding="utf-8") as file:
        data = json.load(file)
    if not isinstance(data, dict):
        raise ToolError("Mock data invalid.")
    return data


def tool_biz_query(args: Dict[str, Any]) -> str:
    entity = args.get("entity", "").strip().lower()
    filters = args.get("filters", {}) or {}
    limit = args.get("limit", 5)

    if entity not in {"customers", "orders"}:
        raise ToolError("Entity must be 'customers' or 'orders'.")
    if not isinstance(filters, dict):
        raise ToolError("Filters must be object.")
    if not isinstance(limit, int):
        raise ToolError("Limit must be integer.")
    if limit < 1 or limit > 50:
        raise ToolError("Limit must be between 1 and 50.")

    allowed_fields = {
        "customers": {"id", "name", "tier", "region"},
        "orders": {"id", "customer_id", "status", "min_amount", "max_amount"},
    }
    for key, value in filters.items():
        if key not in allowed_fields[entity]:
            raise ToolError(f"Unsupported filter: {key}")
        if key in {"min_amount", "max_amount"}:
            if not isinstance(value, (int, float)):
                raise ToolError(f"Filter {key} must be number")
        elif not isinstance(value, (str, int, float)):
            raise ToolError(f"Filter {key} must be string or number")

    data = _load_mock_data()
    records = data.get(entity, [])
    if not isinstance(records, list):
        raise ToolError("Mock data invalid.")

    results: List[Dict[str, Any]] = []
    for record in records:
        if not isinstance(record, dict):
            continue
        if "min_amount" in filters and record.get("amount") is not None:
            if record["amount"] < filters["min_amount"]:
                continue
        if "max_amount" in filters and record.get("amount") is not None:
            if record["amount"] > filters["max_amount"]:
                continue
        matched = True
        for key, value in filters.items():
            if key in {"min_amount", "max_amount"}:
                continue
            if record.get(key) != value:
                matched = False
                break
        if matched:
            results.append(record)
            if len(results) >= limit:
                break

    payload = json.dumps(results, ensure_ascii=False)
    return f"Matched {len(results)} {entity}. {payload}"


def _validate_args(schema: Dict[str, Any], args: Dict[str, Any]) -> None:
    if schema.get("type") != "object":
        return
    properties = schema.get("properties", {})
    required = schema.get("required", [])
    for key in required:
        if key not in args:
            raise ToolError(f"Missing required field: {key}")
    for key, value in args.items():
        if key not in properties:
            raise ToolError(f"Unexpected field: {key}")
        expected = properties[key].get("type")
        if expected == "string" and not isinstance(value, str):
            raise ToolError(f"Field {key} must be string")
        if expected == "number" and not isinstance(value, (int, float)):
            raise ToolError(f"Field {key} must be number")
        if expected == "integer" and not isinstance(value, int):
            raise ToolError(f"Field {key} must be integer")
        if expected == "array" and not isinstance(value, list):
            raise ToolError(f"Field {key} must be array")
        if expected == "object" and not isinstance(value, dict):
            raise ToolError(f"Field {key} must be object")


def _register_tools(registry: ToolRegistry) -> None:
    registry.register(
        ToolSpec(
            name="calc",
            description="Evaluate a simple math expression",
            parameters={
                "type": "object",
                "properties": {
                    "expr": {
                        "type": "string",
                        "description": "Math expression, e.g. 1+2*3",
                    }
                },
                "required": ["expr"],
            },
            handler=tool_calc,
        )
    )
    registry.register(
        ToolSpec(
            name="note_stats",
            description="Show note count and top tags",
            parameters={"type": "object", "properties": {}},
            handler=tool_note_stats,
        )
    )
    registry.register(
        ToolSpec(
            name="biz_query",
            description="Query mock business data",
            parameters={
                "type": "object",
                "properties": {
                    "entity": {
                        "type": "string",
                        "description": "Entity name: customers or orders",
                    },
                    "filters": {
                        "type": "object",
                        "description": "Filter conditions (field:value)",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max results, 1-50",
                    },
                },
                "required": ["entity"],
            },
            handler=tool_biz_query,
        )
    )


def get_registry() -> ToolRegistry:
    registry = ToolRegistry()
    _register_tools(registry)
    return registry


def list_tools() -> List[Dict[str, Any]]:
    return get_registry().list_schemas()


def run_tool(name: str, args: Dict[str, Any]) -> str:
    return get_registry().run(name, args)
