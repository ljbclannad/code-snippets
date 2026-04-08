from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import ModuleType, SimpleNamespace

REPO_ROOT = Path(__file__).resolve().parents[1]
KNOWLEDGE_BASE_DIR = REPO_ROOT / "knowledge-base"

if str(KNOWLEDGE_BASE_DIR) not in sys.path:
    sys.path.insert(0, str(KNOWLEDGE_BASE_DIR))


def _install_langchain_stubs() -> None:
    if "langchain_core.documents" in sys.modules:
        return

    class FakeDocument:
        def __init__(self, page_content: str = "", metadata: dict | None = None):
            self.page_content = page_content
            self.metadata = metadata or {}

    class FakeDashScopeEmbeddings:
        def __init__(self, *args, **kwargs):
            pass

    class FakeFAISS:
        def __init__(self, *args, **kwargs):
            pass

        @classmethod
        def from_documents(cls, *args, **kwargs):
            return cls()

        @classmethod
        def load_local(cls, *args, **kwargs):
            return cls()

        def add_documents(self, *args, **kwargs):
            pass

        def save_local(self, *args, **kwargs):
            pass

    class FakeBM25Retriever:
        def __init__(self):
            self.k = 5

        @classmethod
        def from_documents(cls, *args, **kwargs):
            return cls()

        def invoke(self, query):
            return []

    class FakeMessage:
        def __init__(self, content: str = ""):
            self.content = content

    class FakeChatOpenAI:
        def __init__(self, *args, **kwargs):
            pass

        def invoke(self, messages):
            return SimpleNamespace(content="{}")

    langchain_core = ModuleType("langchain_core")
    langchain_core_documents = ModuleType("langchain_core.documents")
    langchain_core_documents.Document = FakeDocument
    langchain_core_messages = ModuleType("langchain_core.messages")
    langchain_core_messages.SystemMessage = FakeMessage
    langchain_core_messages.HumanMessage = FakeMessage
    langchain_core_language_models = ModuleType("langchain_core.language_models")
    langchain_core_language_models.BaseChatModel = object

    langchain_community = ModuleType("langchain_community")
    langchain_community_embeddings = ModuleType("langchain_community.embeddings")
    langchain_community_embeddings.DashScopeEmbeddings = FakeDashScopeEmbeddings
    langchain_community_retrievers = ModuleType("langchain_community.retrievers")
    langchain_community_retrievers.BM25Retriever = FakeBM25Retriever
    langchain_community_vectorstores = ModuleType("langchain_community.vectorstores")
    langchain_community_vectorstores.FAISS = FakeFAISS

    langchain_openai = ModuleType("langchain_openai")
    langchain_openai_chat_models = ModuleType("langchain_openai.chat_models")
    langchain_openai_chat_models.ChatOpenAI = FakeChatOpenAI

    sys.modules["langchain_core"] = langchain_core
    sys.modules["langchain_core.documents"] = langchain_core_documents
    sys.modules["langchain_core.messages"] = langchain_core_messages
    sys.modules["langchain_core.language_models"] = langchain_core_language_models
    sys.modules["langchain_community"] = langchain_community
    sys.modules["langchain_community.embeddings"] = langchain_community_embeddings
    sys.modules["langchain_community.retrievers"] = langchain_community_retrievers
    sys.modules["langchain_community.vectorstores"] = langchain_community_vectorstores
    sys.modules["langchain_openai"] = langchain_openai
    sys.modules["langchain_openai.chat_models"] = langchain_openai_chat_models


_install_langchain_stubs()

from medical_chunk import chunk_json_with_metadata
from medical_embedding import build_documents
from medical_model_chat import evidence_is_weak
from regression_eval import build_baseline_payload, compare_against_baseline


class RagPipelineTest(unittest.TestCase):
    def test_evidence_is_weak_when_keywords_missing(self) -> None:
        citations = [
            {"content": "本文件主要说明医保支付方式改革。", "snippet": "", "score": 0.8},
        ]
        self.assertTrue(evidence_is_weak("挂网时间要求是什么", citations))

    def test_evidence_is_not_weak_when_keywords_hit(self) -> None:
        citations = [
            {"content": "新增药品挂网工作原则上应在 2025 年 12 月底前完成。", "snippet": "", "score": 0.8},
        ]
        self.assertFalse(evidence_is_weak("新增药品挂网工作什么时候完成", citations))

    def test_chunk_json_with_metadata_preserves_source_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            input_path = Path(tmpdir) / "sample.json"
            payload = [
                {
                    "title": "测试政策",
                    "source": "国家医保局",
                    "url": "https://example.com/policy",
                    "cleaned_content": "第一条。第二条。第三条。",
                }
            ]
            input_path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

            chunks = chunk_json_with_metadata(input_path, max_chars=10, overlap_sentences=1)

            self.assertGreaterEqual(len(chunks), 1)
            self.assertEqual(chunks[0]["source"], "国家医保局")
            self.assertEqual(chunks[0]["source_file"], "sample.json")

    def test_build_documents_carries_metadata(self) -> None:
        docs = build_documents(
            [
                {
                    "id": "1-0",
                    "chunk": "医保目录调整通知",
                    "source": "国家医保局",
                    "url": "https://example.com/policy",
                    "title": "目录调整通知",
                    "pub_year": "2025",
                    "source_file": "sample_chunks.json",
                }
            ],
            source_name="医保政策-药品目录调整通知",
        )

        self.assertEqual(len(docs), 1)
        self.assertEqual(docs[0].metadata["chunk_id"], "1-0")
        self.assertEqual(docs[0].metadata["source"], "国家医保局")
        self.assertEqual(docs[0].metadata["source_url"], "https://example.com/policy")
        self.assertEqual(docs[0].metadata["title"], "目录调整通知")

    def test_build_baseline_payload_uses_selected_metrics(self) -> None:
        rows = [
            {"run_name": "dense-default", "recall@1": 0.4, "mrr@3": 0.5, "hit@3": 0.7},
            {"run_name": "hybrid-rrf", "recall@1": 0.5, "mrr@3": 0.6, "hit@3": 0.8},
        ]
        payload = build_baseline_payload(
            rows,
            compare_metrics=["recall@1", "mrr@3"],
            allowed_drop=0.01,
            config={"data": "eval.json", "index_dir": "index", "ks": [1, 3]},
        )

        self.assertEqual(payload["meta"]["allowed_drop"], 0.01)
        self.assertEqual(payload["runs"]["dense-default"]["recall@1"], 0.4)
        self.assertNotIn("hit@3", payload["runs"]["dense-default"])

    def test_compare_against_baseline_detects_regression(self) -> None:
        rows = [{"run_name": "dense-default", "recall@1": 0.35, "mrr@3": 0.45}]
        baseline = {
            "runs": {
                "dense-default": {
                    "recall@1": 0.4,
                    "mrr@3": 0.44,
                }
            }
        }

        regressions = compare_against_baseline(
            rows,
            baseline,
            compare_metrics=["recall@1", "mrr@3"],
            allowed_drop=0.01,
        )

        self.assertEqual(len(regressions), 1)
        self.assertEqual(regressions[0]["metric"], "recall@1")


if __name__ == "__main__":
    unittest.main()
