# 从零实现 LLM 基础组件

## 项目简介

本项目是一个从零开始实现大语言模型（LLM）基础组件的学习项目，涵盖了 Tokenizer、Dataset、Embedding 等核心模块的原理和实现。通过手写代码的方式，深入理解 LLM 的底层机制。

## 技术栈

- **语言**: Python 3.8+
- **依赖**: 
  - `tiktoken`: GPT-2 BPE 编码
  - `torch`: 深度学习框架
  - 标准库: `re`, `json` 等

## 项目结构

```
from-zero-llm/
├── tokenizer/              # 分词器实现
│   ├── SimpleTokenizer.py      # 简易分词器 V1/V2
│   ├── SimpleTokenizerV1Test.py
│   ├── SimpleTokenizerV2Test.py
│   └── BpeTest.py              # BPE 编码测试
├── dataset/                # 数据集处理
│   ├── GPTDateset.py           # GPT 数据集实现
│   ├── GPTDatesetTest.py
│   └── datasetTest.py
├── embedding/              # 词嵌入
│   ├── embeddingTest.py        # 基础 Embedding 测试
│   └── GPTEmbeddingTest.py     # GPT Embedding 测试
└── the-verdict.txt         # 示例语料
```

## 核心模块

### 1. Tokenizer（分词器）

#### SimpleTokenizerV1

**文件**: `tokenizer/SimpleTokenizer.py`

**功能**: 基于正则表达式的简单分词器

**特点**:
- 使用正则表达式分割文本
- 支持标点符号识别
- 词汇表映射（word ↔ token id）

**核心代码**:
```python
class SimpleTokenizerV1:
    def __init__(self, vocab):
        self.str_to_int = vocab
        self.int_to_str = {v:k for k,v in vocab.items()}
    
    def encode(self, text):
        # 使用正则分割文本
        preprocessed = re.split(r'([,.:;?_!"()\']|--|\s)', text)
        preprocessed = [item.strip() for item in preprocessed if item.strip()]
        integers = [self.str_to_int[word] for word in preprocessed]
        return integers
    
    def decode(self, tokens):
        text = " ".join([self.int_to_str[token] for token in tokens])
        text = re.sub(r'([,.:;?_!"()\'])', r'\1', text)
        return text
```

**使用示例**:
```python
vocab = {"hello": 0, "world": 1, ",": 2}
tokenizer = SimpleTokenizerV1(vocab)
tokens = tokenizer.encode("hello, world")  # [0, 2, 1]
text = tokenizer.decode(tokens)            # "hello, world"
```

#### SimpleTokenizerV2

**改进点**: 增加了未知词（UNK）处理

```python
class SimpleTokenizerV2:
    def encode(self, text):
        preprocessed = re.split(r'([,.:;?_!"()\']|--|\s)', text)
        preprocessed = [item.strip() for item in preprocessed if item.strip()]
        # 未知词替换为 <|unk|>
        preprocessed = [item if item in self.str_to_int 
                       else "<|unk|>" for item in preprocessed]
        integers = [self.str_to_int[word] for word in preprocessed]
        return integers
```

**运行测试**:
```bash
cd from-zero-llm
python tokenizer/SimpleTokenizerV1Test.py
python tokenizer/SimpleTokenizerV2Test.py
```

#### BPE (Byte Pair Encoding)

**文件**: `tokenizer/BpeTest.py`

**功能**: 使用 tiktoken 实现 GPT-2 风格的 BPE 编码

**原理**:
1. 将文本转换为 UTF-8 字节序列
2. 统计相邻字节对的频率
3. 合并频率最高的字节对
4. 重复步骤 2-3 直到达到目标词汇表大小

**核心算法**:
```python
def get_stats(tokens):
    """统计相邻 token 对的频率"""
    stats = {}
    for pair in zip(tokens, tokens[1:]):
        stats[pair] = stats.get(pair, 0) + 1
    return stats

def merge_tokens(pair, tokens, idx):
    """合并指定的 token 对"""
    new_tokens = []
    i = 0
    while i < len(tokens):
        if i < len(tokens) - 1 and tokens[i] == pair[0] and tokens[i+1] == pair[1]:
            new_tokens.append(idx)  # 用新 token 替换
            i += 2
        else:
            new_tokens.append(tokens[i])
            i += 1
    return new_tokens

# BPE 训练过程
vocab_size = 300
num_merges = vocab_size - 256
merged_tokens = {}
ids = tokens

for i in range(num_merges):
    stats = get_stats(ids)
    top_pair = max(stats, key=stats.get)
    idx = 256 + i
    ids = merge_tokens(top_pair, ids, idx)
    merged_tokens[top_pair] = idx
```

**使用 tiktoken**:
```python
import tiktoken

# 加载 GPT-2 编码器
encoder = tiktoken.get_encoding("gpt2")

# 编码
text = "Hello, world!"
tokens = encoder.encode(text)

# 解码
decoded = encoder.decode(tokens)
```

**运行测试**:
```bash
python tokenizer/BpeTest.py
```

---

### 2. Dataset（数据集）

**文件**: `dataset/GPTDateset.py`

**功能**: 实现 GPT 风格的滑动窗口数据集

**核心概念**:
- **上下文窗口**: 模型一次能看到的 token 数量
- **滑动窗口**: 通过滑动窗口生成训练样本
- **输入-目标对**: (input_ids, target_ids)，target 是 input 向右偏移一位

**实现原理**:
```python
class GPTDataset:
    def __init__(self, text, tokenizer, max_length, stride):
        self.tokenizer = tokenizer
        self.max_length = max_length
        self.stride = stride
        
        # 对整个文本进行编码
        self.tokens = tokenizer.encode(text)
        
    def __len__(self):
        # 计算可以生成多少个样本
        return (len(self.tokens) - self.max_length) // self.stride + 1
    
    def __getitem__(self, idx):
        start = idx * self.stride
        end = start + self.max_length
        
        input_ids = self.tokens[start:end]
        target_ids = self.tokens[start+1:end+1]
        
        return torch.tensor(input_ids), torch.tensor(target_ids)
```

**使用示例**:
```python
text = "The quick brown fox jumps over the lazy dog"
tokenizer = SimpleTokenizerV1(vocab)
dataset = GPTDataset(text, tokenizer, max_length=8, stride=4)

# 获取第一个样本
input_ids, target_ids = dataset[0]
# input_ids:  [1, 2, 3, 4, 5, 6, 7, 8]
# target_ids: [2, 3, 4, 5, 6, 7, 8, 9]
```

**参数说明**:
- `max_length`: 上下文窗口大小（如 GPT-2 为 1024）
- `stride`: 滑动步长（stride < max_length 时会有重叠）

**运行测试**:
```bash
python dataset/GPTDatesetTest.py
python dataset/datasetTest.py
```

---

### 3. Embedding（词嵌入）

**文件**: `embedding/embeddingTest.py`, `embedding/GPTEmbeddingTest.py`

**功能**: 将 token id 转换为稠密向量表示

**核心概念**:
- **Token Embedding**: 将 token id 映射到向量空间
- **Position Embedding**: 为每个位置添加位置信息
- **组合**: Token Embedding + Position Embedding

**实现原理**:
```python
import torch.nn as nn

class GPTEmbedding(nn.Module):
    def __init__(self, vocab_size, embed_dim, max_length):
        super().__init__()
        # Token 嵌入层
        self.token_embedding = nn.Embedding(vocab_size, embed_dim)
        # 位置嵌入层
        self.position_embedding = nn.Embedding(max_length, embed_dim)
        
    def forward(self, input_ids):
        batch_size, seq_len = input_ids.shape
        
        # Token 嵌入
        token_embeds = self.token_embedding(input_ids)
        
        # 位置嵌入
        positions = torch.arange(seq_len, device=input_ids.device)
        position_embeds = self.position_embedding(positions)
        
        # 相加得到最终嵌入
        embeddings = token_embeds + position_embeds
        return embeddings
```

**使用示例**:
```python
vocab_size = 50000
embed_dim = 768
max_length = 1024

embedding = GPTEmbedding(vocab_size, embed_dim, max_length)

# 输入 token ids
input_ids = torch.tensor([[1, 2, 3, 4, 5]])  # shape: (1, 5)

# 获取嵌入向量
embeddings = embedding(input_ids)  # shape: (1, 5, 768)
```

**运行测试**:
```bash
python embedding/embeddingTest.py
python embedding/GPTEmbeddingTest.py
```

---

## 快速开始

### 安装依赖

```bash
pip install torch tiktoken
```

### 运行完整流程

```bash
cd from-zero-llm

# 1. 测试分词器
python tokenizer/SimpleTokenizerV1Test.py
python tokenizer/BpeTest.py

# 2. 测试数据集
python dataset/GPTDatesetTest.py

# 3. 测试嵌入层
python embedding/GPTEmbeddingTest.py
```

## 学习路径

### 第一阶段：理解 Tokenizer

1. 阅读 `SimpleTokenizer.py`，理解基于规则的分词
2. 运行 `SimpleTokenizerV1Test.py` 和 `SimpleTokenizerV2Test.py`
3. 学习 BPE 算法原理，运行 `BpeTest.py`
4. 对比不同分词方法的优缺点

**关键问题**:
- 为什么需要 BPE？
- BPE 如何平衡词汇表大小和表达能力？
- 未知词（UNK）如何处理？

### 第二阶段：理解 Dataset

1. 阅读 `GPTDateset.py`，理解滑动窗口机制
2. 运行 `GPTDatesetTest.py`，观察输入-目标对的生成
3. 实验不同的 `max_length` 和 `stride` 参数

**关键问题**:
- 为什么 target 是 input 向右偏移一位？
- stride 如何影响训练样本的数量和重叠度？
- 如何处理文本长度不足 max_length 的情况？

### 第三阶段：理解 Embedding

1. 阅读 `embeddingTest.py`，理解 Token Embedding
2. 阅读 `GPTEmbeddingTest.py`，理解 Position Embedding
3. 实验不同的 `embed_dim` 参数

**关键问题**:
- 为什么需要 Position Embedding？
- Token Embedding 和 Position Embedding 如何组合？
- 嵌入维度如何影响模型性能？

## 核心概念

### 1. Tokenization（分词）

**目的**: 将文本转换为模型可以处理的数字序列

**方法**:
- **基于规则**: 正则表达式、空格分割
- **基于统计**: BPE、WordPiece、Unigram
- **基于神经网络**: SentencePiece

### 2. Byte Pair Encoding (BPE)

**优势**:
- 平衡词汇表大小和表达能力
- 处理未登录词（OOV）
- 支持多语言

**训练过程**:
1. 初始词汇表为所有字节（256 个）
2. 统计相邻 token 对的频率
3. 合并频率最高的 token 对
4. 重复直到达到目标词汇表大小

### 3. 滑动窗口数据集

**作用**: 从长文本中生成固定长度的训练样本

**参数**:
- `max_length`: 上下文窗口大小
- `stride`: 滑动步长

**示例**:
```
文本: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
max_length: 4
stride: 2

样本 1: input=[1,2,3,4], target=[2,3,4,5]
样本 2: input=[3,4,5,6], target=[4,5,6,7]
样本 3: input=[5,6,7,8], target=[6,7,8,9]
```

### 4. 词嵌入

**Token Embedding**: 将离散的 token id 映射到连续的向量空间

**Position Embedding**: 为每个位置添加位置信息，使模型能够区分不同位置的相同 token

**组合方式**:
- **相加**: GPT 系列（本项目采用）
- **拼接**: BERT 系列

## 扩展实验

### 实验 1: 自定义词汇表

```python
# 构建自定义词汇表
text = open("the-verdict.txt", "r").read()
words = sorted(set(re.findall(r'\w+|[^\w\s]', text)))
vocab = {word: idx for idx, word in enumerate(words)}

# 使用自定义词汇表
tokenizer = SimpleTokenizerV1(vocab)
```

### 实验 2: 不同的 BPE 词汇表大小

```python
# 小词汇表（压缩率高，但表达能力弱）
vocab_size = 300

# 大词汇表（压缩率低，但表达能力强）
vocab_size = 50000
```

### 实验 3: 不同的滑动窗口策略

```python
# 无重叠（stride = max_length）
dataset = GPTDataset(text, tokenizer, max_length=8, stride=8)

# 50% 重叠（stride = max_length / 2）
dataset = GPTDataset(text, tokenizer, max_length=8, stride=4)

# 高度重叠（stride = 1）
dataset = GPTDataset(text, tokenizer, max_length=8, stride=1)
```

## 常见问题

### Q: 为什么 GPT 使用 BPE 而不是词级别的分词？

A: BPE 的优势在于：
1. 词汇表大小可控
2. 能够处理未登录词
3. 支持多语言
4. 平衡了字符级和词级的优缺点

### Q: Position Embedding 为什么要和 Token Embedding 相加？

A: 相加的好处：
1. 保持嵌入维度不变
2. 允许位置信息和语义信息融合
3. 计算效率高

### Q: 如何选择合适的 max_length？

A: 考虑因素：
1. 任务需求（短文本 vs 长文本）
2. 计算资源（更长的序列需要更多内存）
3. 模型架构（Transformer 的自注意力复杂度是 O(n²)）

## 相关资源

- [GPT-2 论文](https://d4mucfpksywv.cloudfront.net/better-language-models/language_models_are_unsupervised_multitask_learners.pdf)
- [BPE 原始论文](https://arxiv.org/abs/1508.07909)
- [tiktoken 文档](https://github.com/openai/tiktoken)
- [Hugging Face Tokenizers](https://huggingface.co/docs/tokenizers/index)

## 许可证

本项目仅供学习和演示使用。
