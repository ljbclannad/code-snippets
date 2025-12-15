import sys
from pathlib import Path
PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

import tiktoken
from dataset.GPTDateset import GPTDatesetV1
from torch.utils.data import DataLoader

def create_dataloader_v1(txt,batch_size=4,max_length=256,stride=128,
shuffle=True,drop_last=True,num_workers=0):
    # 创建分词器
    tokenizer = tiktoken.get_encoding("gpt2")
    # 通过分词器返回的token创建数据集
    dataset = GPTDatesetV1(txt,tokenizer,max_length,stride)
    # 创建数据加载器
    dataloader = DataLoader(
        dataset,
        batch_size=batch_size,
        shuffle=shuffle,
        drop_last=drop_last,
        num_workers=num_workers, # CPU进程数
    )
    return dataloader

with open("../the-verdict.txt", "r", encoding="utf-8") as f:
    raw_text = f.read()

dataloader = create_dataloader_v1(raw_text,batch_size=8,
    max_length=4,stride=4,shuffle=False,drop_last=False)
data_iter = iter(dataloader)
first_batch = next(data_iter)
# print(first_batch)

second_batch = next(data_iter)
# print(second_batch)