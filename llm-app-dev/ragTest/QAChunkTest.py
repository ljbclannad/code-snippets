import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

from langchain_community.document_loaders import PyPDFLoader
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_openai.chat_models import ChatOpenAI
from langchain_text_splitters import RecursiveCharacterTextSplitter


@dataclass
class QAPair:
    question: str
    answer: str
    chunk_id: int
    metadata: Dict[str, Any]
    embedding: Optional[List[float]] = None

    def to_record(self) -> Dict[str, Any]:
        record: Dict[str, Any] = {
            "chunk_id": self.chunk_id,
            "question": self.question,
            "answer": self.answer,
            "metadata": self.metadata,
        }
        if self.embedding is not None:
            record["embedding"] = self.embedding
        return record


class QAChunkGenerator:
    """基于文档切分与 LLM 的 QA 预生成器。"""

    def __init__(
        self,
        pdf_path: Path,
        embeddings: DashScopeEmbeddings,
        chat_model: ChatOpenAI,
        chunk_size: int = 800,
        chunk_overlap: int = 120,
        questions_per_chunk: int = 3,
        embed_questions: bool = True,
    ) -> None:
        self.pdf_path = pdf_path
        self.embeddings = embeddings
        self.chat_model = chat_model
        self.questions_per_chunk = questions_per_chunk
        self.embed_questions = embed_questions
        self.text_splitter = RecursiveCharacterTextSplitter(
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
            add_start_index=True,
        )

    def generate_dataset(
        self,
        output_path: Optional[Path] = None,
        max_chunks: Optional[int] = None,
    ) -> List[QAPair]:
        """生成 QA 数据集，可选落盘。

        Args:
            output_path: 写入 JSON 的目标路径；None 时仅返回内存结果。
            max_chunks: 限制处理的 chunk 数量，None 表示全部。
        """
        docs = self._load_documents()
        chunks = self.text_splitter.split_documents(docs)
        qa_pairs: List[QAPair] = []

        for idx, chunk in enumerate(chunks):
            if max_chunks is not None and idx >= max_chunks:
                break

            try:
                qa_pairs.extend(self._generate_for_chunk(chunk, idx))
            except Exception as exc:  # noqa: BLE001
                print(f"[WARN] Chunk {idx} QA 生成失败：{exc}")

        if output_path:
            self._persist(output_path, qa_pairs)

        return qa_pairs

    def _load_documents(self):
        if not self.pdf_path.exists():
            raise FileNotFoundError(f"未找到 PDF 文件：{self.pdf_path}")
        loader = PyPDFLoader(str(self.pdf_path))
        return loader.load()

    def _generate_for_chunk(self, chunk, chunk_id: int) -> List[QAPair]:
        """对单个 chunk 调用 LLM 生成多组问答并附带可选 embedding。"""
        messages = [
            {
                "role": "system",
                "content": (
                    "你是企业知识库构建助手，需要依据给定原文生成高质量 QA。"
                    "问题要覆盖原文关键信息，答案须来自原文且语言精炼。"
                ),
            },
            {
                "role": "user",
                "content": (
                    f"请基于 chunk#{chunk_id} 的原文内容生成 {self.questions_per_chunk} 组问答。\n"
                    "输出 JSON 数组，元素格式："
                    '{"question": "...", "answer": "..."}。\n'
                    "禁止虚构信息，答案必须可在原文中找到。\n"
                    f"原文：\n{chunk.page_content}"
                ),
            },
        ]

        response = self.chat_model.invoke(messages)
        payload = self._parse_response(response.content)

        metadata = dict(chunk.metadata)
        metadata["chunk_id"] = chunk_id

        qa_items: List[QAPair] = []
        for item in payload:
            question = item.get("question")
            answer = item.get("answer")
            if not question or not answer:
                continue
            embedding = (
                self.embeddings.embed_query(question) if self.embed_questions else None
            )
            qa_items.append(
                QAPair(
                    question=question.strip(),
                    answer=answer.strip(),
                    chunk_id=chunk_id,
                    metadata=metadata,
                    embedding=embedding,
                )
            )
        return qa_items

    @staticmethod
    def _parse_response(raw_text: str) -> List[Dict[str, Any]]:
        """从模型原始输出中提取 JSON 数组内容。"""
        text = raw_text.strip()
        if text.startswith("```"):
            text = text.strip("`")
            if text.startswith("json"):
                text = text[len("json") :].strip()

        start = text.find("[")
        end = text.rfind("]")
        if start == -1 or end == -1:
            raise ValueError("模型输出中缺少有效的 JSON 数组。")
        text = text[start : end + 1]

        data = json.loads(text)
        if not isinstance(data, list):
            raise ValueError("解析结果不是 JSON 数组。")
        return data

    @staticmethod
    def _persist(path: Path, qa_pairs: List[QAPair]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = [pair.to_record() for pair in qa_pairs]
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


embeddings = DashScopeEmbeddings(
    model="text-embedding-v4",
)

chat_model = ChatOpenAI(
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    model="deepseek-chat",
    base_url="https://api.deepseek.com",
)

BASE_DIR = Path(__file__).resolve().parent
PDF_PATH = BASE_DIR / "test.pdf"
OUTPUT_PATH = BASE_DIR / "qa_dataset.json"


def run_generation() -> None:
    generator = QAChunkGenerator(
        pdf_path=PDF_PATH,
        embeddings=embeddings,
        chat_model=chat_model,
        chunk_size=800,
        chunk_overlap=120,
        questions_per_chunk=3,
    )
    qa_pairs = generator.generate_dataset(
        output_path=OUTPUT_PATH,
        max_chunks=5,
    )
    print(f"已成功生成 {len(qa_pairs)} 组 QA，结果保存至：{OUTPUT_PATH}")


if __name__ == "__main__":
    run_generation()
