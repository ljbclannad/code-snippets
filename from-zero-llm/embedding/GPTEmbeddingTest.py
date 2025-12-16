import torch

import sys
from pathlib import Path
PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from dataset.GPTDatesetTest import create_dataloader_v1

vocab_size = 502547
output_dim = 256
embedding_layer = torch.nn.Embedding(vocab_size, output_dim)
with open("../the-verdict.txt", "r", encoding="utf-8") as f:
    raw_text = f.read()
max_length = 4
dataloader = create_dataloader_v1(
        raw_text,
        batch_size=8,
        max_length=max_length,
        stride=max_length,
        shuffle=False,
        drop_last=False,
    )
data_iter = iter(dataloader)
inputs, targets = next(data_iter)
token_embeddings = embedding_layer(inputs)
# (batch_size, max_length, output_dim)
print(token_embeddings.shape)

# 位置编码
context_length = max_length
pos_embedding_layer = torch.nn.Embedding(context_length, output_dim)
pos_embeddings = pos_embedding_layer(torch.arange(context_length))
print(pos_embeddings.shape)

# 输入嵌入
input_embeddings = token_embeddings + pos_embeddings
print(input_embeddings.shape)