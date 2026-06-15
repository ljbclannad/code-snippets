import math
import os
from contextlib import asynccontextmanager
from typing import List

import torch
from fastapi import FastAPI
from pydantic import BaseModel, Field
from transformers import AutoModelForSequenceClassification, AutoTokenizer


MODEL_NAME = os.getenv("RERANK_MODEL_NAME", "BAAI/bge-reranker-v2-m3")
DEVICE = os.getenv("RERANK_DEVICE", "cpu")
BATCH_SIZE = int(os.getenv("RERANK_BATCH_SIZE", "8"))
MAX_LENGTH = int(os.getenv("RERANK_MAX_LENGTH", "1024"))

tokenizer = None
model = None


class RerankRequest(BaseModel):
    model: str = Field(default=MODEL_NAME)
    query: str
    documents: List[str]


class RerankResult(BaseModel):
    index: int
    relevance_score: float
    document: str


class RerankResponse(BaseModel):
    id: str
    results: List[RerankResult]


def _select_device() -> str:
    if DEVICE != "auto":
        return DEVICE
    return "cuda" if torch.cuda.is_available() else "cpu"


def _load_model() -> None:
    global tokenizer, model
    runtime_device = _select_device()
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    model = AutoModelForSequenceClassification.from_pretrained(MODEL_NAME)
    model.to(runtime_device)
    model.eval()


def _score_pairs(query: str, documents: List[str]) -> List[float]:
    runtime_device = next(model.parameters()).device
    scores: List[float] = []

    for start in range(0, len(documents), BATCH_SIZE):
        batch_docs = documents[start:start + BATCH_SIZE]
        pairs = [[query, document] for document in batch_docs]
        encoded = tokenizer(
            pairs,
            padding=True,
            truncation=True,
            max_length=MAX_LENGTH,
            return_tensors="pt",
        )
        encoded = {key: value.to(runtime_device) for key, value in encoded.items()}
        with torch.no_grad():
            logits = model(**encoded).logits.view(-1).float().cpu().tolist()
        scores.extend([1.0 / (1.0 + math.exp(-value)) for value in logits])

    return scores


@asynccontextmanager
async def lifespan(_: FastAPI):
    _load_model()
    yield


app = FastAPI(title="BGE Rerank Service", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME}


@app.post("/v1/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest):
    if tokenizer is None or model is None:
        raise RuntimeError("Model is not loaded")

    documents = request.documents or []
    if not documents:
        return RerankResponse(id=request.model, results=[])

    scores = _score_pairs(request.query, documents)
    results = [
        RerankResult(index=index, relevance_score=score, document=documents[index])
        for index, score in enumerate(scores)
    ]
    results.sort(key=lambda item: item.relevance_score, reverse=True)
    return RerankResponse(id=request.model, results=results)
