import argparse
import json
import sys
from datetime import datetime
from pathlib import Path


BASE_DIR = Path(__file__).resolve().parents[1]
KB_DIR = BASE_DIR / "knowledge-base"
sys.path.insert(0, str(KB_DIR))

from build_index import build_index  # noqa: E402
from query import load_index, search  # noqa: E402
from tools import ToolError, list_tools, run_tool  # noqa: E402


def append_note(notes_path: Path, text: str, tags: list) -> str:
    note_id = f"note-{datetime.now().strftime('%Y%m%d%H%M%S')}"
    note = {"id": note_id, "text": text, "tags": tags}
    with open(notes_path, "a", encoding="utf-8") as file:
        file.write(json.dumps(note, ensure_ascii=False) + "\n")
    return note_id


def handle_learn(args: argparse.Namespace) -> None:
    notes_path = KB_DIR / "notes.jsonl"
    note_id = append_note(notes_path, args.text, args.tags)
    print(f"Saved note: {note_id}")
    if args.rebuild:
        exit_code = build_index(notes_path, KB_DIR / "index.json")
        if exit_code == 0:
            print("Index rebuilt.")


def handle_ask(args: argparse.Namespace) -> None:
    index_path = KB_DIR / "index.json"
    if not index_path.exists():
        print("Index not found. Run build_index.py first.")
        return

    index = load_index(index_path)
    results = search(index, args.question, top_k=args.top_k)
    if not results:
        print("No results.")
        return

    top_score, top_doc = results[0]
    print(f"Top result: {top_doc.get('id', '')} ({top_score:.3f})")
    print(f"Answer: {top_doc.get('text', '')}")

    if args.show_matches:
        for score, doc in results[1:]:
            print(f"- {doc.get('id', '')} ({score:.3f}): {doc.get('text', '')}")


def handle_tools(_: argparse.Namespace) -> None:
    for tool in list_tools():
        args = ", ".join(f"{key}:{value}" for key, value in tool["args"].items())
        args = args or "no args"
        print(f"- {tool['name']}: {tool['description']} ({args})")


def handle_tool(args: argparse.Namespace) -> None:
    payload = {}
    if args.payload:
        try:
            payload = json.loads(args.payload)
        except json.JSONDecodeError:
            print("Invalid JSON for --payload.")
            return
    try:
        result = run_tool(args.name, payload)
        print(result)
    except ToolError as error:
        print(f"Tool error: {error}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Learning Agent CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    learn_parser = subparsers.add_parser("learn", help="Add a note to knowledge base")
    learn_parser.add_argument("--text", required=True, help="Note content")
    learn_parser.add_argument(
        "--tags",
        default="",
        help="Comma-separated tags, e.g. agent,summary",
    )
    learn_parser.add_argument(
        "--rebuild",
        action="store_true",
        help="Rebuild index after learning",
    )
    learn_parser.set_defaults(func=handle_learn)

    ask_parser = subparsers.add_parser("ask", help="Ask a question")
    ask_parser.add_argument("question", help="User question")
    ask_parser.add_argument("--top-k", type=int, default=3, help="Top K results")
    ask_parser.add_argument(
        "--show-matches", action="store_true", help="Show extra matches"
    )
    ask_parser.set_defaults(func=handle_ask)

    tools_parser = subparsers.add_parser("tools", help="List available tools")
    tools_parser.set_defaults(func=handle_tools)

    tool_parser = subparsers.add_parser("tool", help="Run a tool")
    tool_parser.add_argument("name", help="Tool name")
    tool_parser.add_argument(
        "--payload",
        default="",
        help="JSON payload string, e.g. '{\"expr\": \"1+2\"}'",
    )
    tool_parser.set_defaults(func=handle_tool)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    if hasattr(args, "tags"):
        args.tags = [tag.strip() for tag in args.tags.split(",") if tag.strip()]
    args.func(args)


if __name__ == "__main__":
    main()
