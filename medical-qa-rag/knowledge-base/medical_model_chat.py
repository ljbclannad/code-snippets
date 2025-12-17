
import os
import re
from typing import Any, Dict, List, Tuple, Optional

from langchain_openai.chat_models import ChatOpenAI
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.vectorstores import FAISS
from langchain_core.documents import Document
from langchain_core.messages import SystemMessage, HumanMessage


# ----------------------------
# 1) 初始化：LLM（DeepSeek）
# ----------------------------
chat_model = ChatOpenAI(
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    model="deepseek-chat",
    base_url="https://api.deepseek.com",
)

# ----------------------------
# 2) 加载 FAISS 索引
#    注意：FAISS.load_local 需要传入 embeddings（用于把 query 向量化）
# ----------------------------
def load_faiss(index_dir: str) -> FAISS:
    if not os.getenv("DASHSCOPE_API_KEY"):
        raise EnvironmentError("请先设置环境变量 DASHSCOPE_API_KEY（用于 query embedding）")
    embeddings = DashScopeEmbeddings(model="text-embedding-v4")
    vs = FAISS.load_local(
        index_dir,
        embeddings,
        allow_dangerous_deserialization=True,  # 本地可信索引可用
    )
    return vs


# ----------------------------
# 3) 检索：拿到 docs + scores
#    LangChain 的 FAISS: similarity_search_with_score 返回 (doc, score)
#    score 通常是“距离”，越小越相似（不同版本封装可能略有差异）
# ----------------------------
def retrieve(
    vs: FAISS,
    query: str,
    k: int = 5,
) -> List[Tuple[Document, float]]:
    return vs.similarity_search_with_score(query, k=k)


# ----------------------------
# 4) 引用结构化：source_url / chunk_id / snippet
# ----------------------------
def build_citations(
    retrieved: List[Tuple[Document, float]],
    snippet_chars: int = 220,
) -> List[Dict[str, Any]]:
    cites: List[Dict[str, Any]] = []
    for i, (doc, score) in enumerate(retrieved, 1):
        md = doc.metadata or {}
        text = (doc.page_content or "").strip()

        # 你入库时 metadata 可能只有 source / chunk_id
        # 如果你后续能提供真实 URL，就放 metadata["source_url"]
        source_url = md.get("source_url") or md.get("source") or "unknown_source"
        chunk_id = md.get("chunk_id", md.get("id", None))

        cites.append(
            {
                "rank": i,
                "score": score,
                "source_url": source_url,
                "chunk_id": chunk_id,
                "content": text,
                "snippet": text[:snippet_chars] + ("..." if len(text) > snippet_chars else ""),
            }
        )
    return cites


# ----------------------------
# 5) 简单的“证据充分性”判断（工程里你可以更严格）
#    - 没检索到：直接证据不足
#    - 检索到了但内容明显不相关：也提示证据不足
# ----------------------------
def evidence_is_weak(query: str, citations: List[Dict[str, Any]]) -> bool:
    if not citations:
        return True

    # 粗糙但实用：看 query 关键词是否在 Top1/Top2 snippet 中出现
    keywords = [w for w in re.split(r"\s+|,|，|。|？|\?|！|!", query) if len(w) >= 2]
    top_snips = " ".join(c["content"] for c in citations[:5])
    hit = 1 if any(kw in top_snips for kw in keywords) else 0
    # 命中太少，就认为证据偏弱（你可以调阈值）
    return hit > 5


# ----------------------------
# 6) 简单冲突检测（演示用）
#    比如同一个问题检索到不同年份/不同截止日期，提示“需人工核对”
# ----------------------------
DATE_RE = re.compile(r"(\d{4})年(\d{1,2})月(\d{1,2})日|(\d{4})年(\d{1,2})月底")

def detect_conflict(citations: List[Dict[str, Any]]) -> Optional[str]:
    found = set()
    for c in citations[:5]:
        for m in DATE_RE.finditer(c["content"]):
            found.add(m.group(0))
    if len(found) >= 2:
        return f"检索到多个可能的日期/期限表述：{sorted(found)}，存在口径不一致风险，建议人工复核原文。"
    return None


# ----------------------------
# 7) RAG 问答：answer + 引用列表
#    - 强约束：只能基于证据回答
#    - 证据不足：明确说明，避免幻觉
# ----------------------------
def rag_qa(
    vs: FAISS,
    question: str,
    k: int = 5,
) -> Dict[str, Any]:
    retrieved = retrieve(vs, question, k=k)
    citations = build_citations(retrieved)

    # 证据不足/不相关：直接拒答或要求补充
    if evidence_is_weak(question, citations):
        return {
            "question": question,
            "answer": "我在当前知识库检索到的证据不足以可靠回答这个问题。你可以补充：政策文件名称/地区/时间范围，或把相关原文段落提供给我，我再基于证据回答。",
            "citations": citations,
            "notes": "evidence_insufficient",
        }

    conflict_note = detect_conflict(citations)

    # 给模型的“证据上下文”
    # 用 [1]/[2] 做标号，方便模型在回答里引用（同时我们也返回结构化引用列表）
    context_lines = []
    for c in citations:
        context_lines.append(
            f"[{c['rank']}] (source={c['source_url']}, chunk_id={c['chunk_id']}) {c['content']}"
        )
    context = "\n".join(context_lines)

    system = SystemMessage(
        content=(
            "你是一个严谨的医疗健康政策问答助手。\n"
            "必须只基于给定【证据】回答，不要编造。\n"
            "如果证据不足，明确说“证据不足”，并说明需要什么信息。\n"
            "回答要简洁；给出结论后，列出引用编号，如：依据[1][2]。\n"
        )
    )
    user = HumanMessage(
        content=(
            f"问题：{question}\n\n"
            f"【证据】\n{context}\n\n"
            "请基于证据作答：\n"
            "1) 先给出结论\n"
            "2) 再给出依据（引用编号）\n"
            "3) 若存在冲突或口径不一致，请提示风险\n"
        )
    )

    resp = chat_model.invoke([system, user])
    answer_text = (resp.content or "").strip()

    # 把冲突提示附加到 notes（也可以让模型自己说，这里双保险）
    notes = []
    if conflict_note:
        notes.append(conflict_note)

    return {
        "question": question,
        "answer": answer_text,
        "citations": citations,  # 结构化引用：source_url/chunk_id/snippet
        "notes": notes,
    }


# ----------------------------
# 8) 模拟多个 QA（多种情况）
# ----------------------------
def run_demo(index_dir: str) -> None:
    vs = load_faiss(index_dir)

    questions = [
        # 情况 A：典型“命中明确条款/期限”的问题（你贴的 chunk 里就有 2025年12月底前）
        "商业健康保险创新药品目录是第几年更新的？",

        # 情况 B：从同一主题多段证据综合（挂网价格/支付标准约束）
        # "谈判药品挂网价格与新版目录确定的支付标准有什么关系？",

        # 情况 C：知识库可能没有的“离谱/缺证据”问题 -> 应拒答/提示补充
        # "2027年医保目录新增药品必须在1月底前挂网吗？",

        # 情况 D：容易出现“口径冲突/时间不一致”的提问 -> 提醒风险
        # "医保定点医疗机构需要在什么时候召开药事会并调整药品配备？",
    ]

    for q in questions:
        result = rag_qa(vs, q, k=5)
        print("\n" + "=" * 90)
        print("Q:", result["question"])
        print("\nA:\n", result["answer"])
        # if result.get("notes"):
        #     print("\n[NOTES]")
        #     for n in result["notes"]:
        #         print("-", n)

        print("\n[CITATIONS]")
        for c in result["citations"]:
            print(
                f"- rank={c['rank']} score={c['score']:.4f} "
                f"source_url={c['source_url']} chunk_id={c['chunk_id']}\n"
                f"  snippet: {c['snippet']}\n"
                f"  content: {c['content']}\n"
            )


if __name__ == "__main__":
    # 改成你保存 FAISS 的目录（里面应有 index.faiss + index.pkl）
    INDEX_DIR = "/Users/lejinbo/LLM/code-snippets/medical_docs/medical_documents_20251217_104142_cleaned_faiss_index"
    run_demo(INDEX_DIR)
