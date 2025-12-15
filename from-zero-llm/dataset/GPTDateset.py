import torch
from torch.utils.data import Dataset, DataLoader

import sys
from pathlib import Path
PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

    
class GPTDatesetV1(Dataset):
    def __init__(self, txt,tokenizer,max_length,stride):
        self.input_ids = []
        self.target_ids = []

        token_ids = tokenizer.encode(txt)
        # stride是步长，用于控制滑动窗口的步长，类似于i=i+stride
        for i in range(0, len(token_ids) - max_length, stride):
            # 使用滑动窗口将文本划分为长度为max_length的重叠序列
            input_chunk = token_ids[i:i+max_length]
            target_chunk = token_ids[i+1:i+max_length+1]
            self.input_ids.append(torch.tensor(input_chunk))
            self.target_ids.append(torch.tensor(target_chunk))

    def __len__(self):
        return len(self.input_ids)

    def __getitem__(self, idx):
        return self.input_ids[idx], self.target_ids[idx]