# Tokenization 实验集合

## 项目简介

本项目是一个全面的分词（Tokenization）实验集合，涵盖了从基础的 BPE 算法到现代 Transformer 模型的 Tokenizer 实现。通过多种实验和示例，深入理解不同分词方法的原理和应用。

## 技术栈

- **语言**: Python 3.8+
- **依赖**:
  - `transformers`: Hugging Face Tokenizers
  - `sentencepiece`: Google SentencePiece
  - `tiktoken`: OpenAI GPT-2 Encoder
  - 标准库: `re`, `json`

## 项目结构

```
tokenization/
├── bpe.py                      # BPE 算法实现
├── regexTest.py                # 正则表达式分词测试
├── encoderGpt2.py              # GPT-2 Encoder 实现
├── encoder.json                # GPT-2 词汇表
├── vocab.bpe                   # GPT-2 BPE 合并规则
├── deepseekr1/                 # DeepSeek R1 Tokenizer
│   ├── tokenizerTest.py
│   ├── tokenizer.json
│   └── tokenizer_config.json
├── sentencepiece/              # SentencePiece 示例
│   └── sentencepieceTest.py
└── tokenization.png            # 分词流程示意图
```

## 核心模块

### 1. BPE (Byte Pair Encoding)

**文件**: `bpe.py`

**原理**: 字节级别的 BPE 算法，通过迭代合并高频字节对来构建词汇表。

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
text = "Species Differences: Grévy's zebra..."
tokens = list(text.encode("utf-8"))  # 转换为字节序列

vocab_size = 300
num_merges = vocab_size - 256
merged_tokens = {}
ids = tokens

for i in range(num_merges):
    stats = get_stats(ids)
    top_pair = max(stats, key=stats.get)  # 找到频率最高的 pair
    idx = 256 + i
    ids = merge_tokens(top_pair, ids, idx)
    merged_tokens[top_pair] = idx
```

**训练流程**:
1. 将文本转换为 UTF-8 字节序列（0-255）
2. 统计所有相邻字节对的频率
3. 合并频率最高的字节对，分配新的 token id
4. 重复步骤 2-3，直到达到目标词汇表大小

**运行示例**:
```bash
python bpe.py
```

**输出示例**:
```
原始文本长度: 245
编码后 token 数: 180
压缩率: 26.5%
```

---

### 2. GPT-2 Encoder

**文件**: `encoderGpt2.py`, `encoder.json`, `vocab.bpe`

**功能**: 完整实现 GPT-2 的 BPE Tokenizer

**核心组件**:
- `encoder.json`: 词汇表（token → id 映射）
- `vocab.bpe`: BPE 合并规则
- `encoderGpt2.py`: 编码/解码实现

**实现原理**:

```python
import json
import re

class GPT2Encoder:
    def __init__(self):
        # 加载词汇表
        with open('encoder.json', 'r') as f:
            self.encoder = json.load(f)
        self.decoder = {v: k for k, v in self.encoder.items()}
        
        # 加载 BPE 合并规则
        with open('vocab.bpe', 'r', encoding='utf-8') as f:
            bpe_data = f.read()
        bpe_merges = [tuple(merge.split()) for merge in bpe_data.split('\n')[1:-1]]
        self.bpe_ranks = dict(zip(bpe_merges, range(len(bpe_merges))))
    
    def encode(self, text):
        """将文本编码为 token ids"""
        # 1. 使用正则表达式分割文本
        tokens = re.findall(r"'s|'t|'re|'ve|'m|'ll|'d| ?\w+| ?[^\s\w]+|\s+", text)
        
        # 2. 对每个 token 应用 BPE
        bpe_tokens = []
        for token in tokens:
            bpe_tokens.extend(self.bpe(token))
        
        # 3. 转换为 ids
        return [self.encoder[token] for token in bpe_tokens]
    
    def decode(self, tokens):
        """将 token ids 解码为文本"""
        text = ''.join([self.decoder[token] for token in tokens])
        return text
    
    def bpe(self, token):
        """对单个 token 应用 BPE"""
        word = tuple(token)
        pairs = get_pairs(word)
        
        if not pairs:
            return [token]
        
        while True:
            # 找到 rank 最小的 pair（最早被合并的）
            bigram = min(pairs, key=lambda pair: self.bpe_ranks.get(pair, float('inf')))
            if bigram not in self.bpe_ranks:
                break
            
            # 合并 bigram
            first, second = bigram
            new_word = []
            i = 0
            while i < len(word):
                if i < len(word) - 1 and word[i] == first and word[i+1] == second:
                    new_word.append(first + second)
                    i += 2
                else:
                    new_word.append(word[i])
                    i += 1
            
            word = tuple(new_word)
            if len(word) == 1:
                break
            pairs = get_pairs(word)
        
        return word
```

**使用示例**:
```python
encoder = GPT2Encoder()

text = "Hello, world! This is a test."
tokens = encoder.encode(text)
print(f"Tokens: {tokens}")

decoded = encoder.decode(tokens)
print(f"Decoded: {decoded}")
```

**运行示例**:
```bash
python encoderGpt2.py
```

---

### 3. 正则表达式分词

**文件**: `regexTest.py`

**功能**: 演示不同正则表达式模式对分词结果的影响

**常见模式**:

```python
import re

text = "Hello, world! This is a test. It's 2024."

# 模式 1: 简单空格分割
pattern1 = r'\s+'
tokens1 = re.split(pattern1, text)
# ['Hello,', 'world!', 'This', 'is', 'a', 'test.', "It's", '2024.']

# 模式 2: 保留标点符号
pattern2 = r'([,.:;?_!"()\']|--|\s)'
tokens2 = re.split(pattern2, text)
# ['Hello', ',', ' ', 'world', '!', ' ', 'This', ...]

# 模式 3: GPT-2 风格（处理缩写）
pattern3 = r"'s|'t|'re|'ve|'m|'ll|'d| ?\w+| ?[^\s\w]+|\s+"
tokens3 = re.findall(pattern3, text)
# ['Hello', ',', ' world', '!', ' This', ' is', ' a', ' test', '.', " It", "'s", ' 2024', '.']

# 模式 4: 处理多语言
pattern4 = r'\w+|[^\w\s]'
tokens4 = re.findall(pattern4, text)
# ['Hello', ',', 'world', '!', 'This', 'is', 'a', 'test', '.', 'It', 's', '2024', '.']
```

**关键点**:
- GPT-2 使用特殊的正则模式处理缩写（'s, 't, 're 等）
- 空格的处理方式影响 token 的边界
- 不同语言需要不同的正则模式

**运行示例**:
```bash
python regexTest.py
```

---

### 4. DeepSeek R1 Tokenizer

**文件**: `deepseekr1/tokenizerTest.py`

**功能**: 加载和测试 DeepSeek R1 模型的 Tokenizer

**实现**:

```python
from transformers import AutoTokenizer

# 加载本地 tokenizer
tokenizer = AutoTokenizer.from_pretrained("./deepseekr1")

# 编码
text = "你好，世界！Hello, world!"
tokens = tokenizer.encode(text)
print(f"Token IDs: {tokens}")

# 解码
decoded = tokenizer.decode(tokens)
print(f"Decoded: {decoded}")

# 查看 token 详情
token_details = tokenizer.tokenize(text)
print(f"Tokens: {token_details}")

# 词汇表大小
print(f"Vocab size: {tokenizer.vocab_size}")

# 特殊 token
print(f"BOS token: {tokenizer.bos_token}")
print(f"EOS token: {tokenizer.eos_token}")
print(f"PAD token: {tokenizer.pad_token}")
print(f"UNK token: {tokenizer.unk_token}")
```

**配置文件**:
- `tokenizer.json`: 完整的 tokenizer 配置
- `tokenizer_config.json`: 元数据和特殊 token 配置

**运行示例**:
```bash
python deepseekr1/tokenizerTest.py
```

---

### 5. SentencePiece

**文件**: `sentencepiece/sentencepieceTest.py`

**功能**: 使用 Google SentencePiece 进行分词

**特点**:
- 语言无关（Language-agnostic）
- 支持 BPE 和 Unigram 算法
- 直接从原始文本训练
- 无需预分词

**训练 SentencePiece 模型**:

```python
import sentencepiece as spm

# 训练模型
spm.SentencePieceTrainer.train(
    input='corpus.txt',
    model_prefix='m',
    vocab_size=8000,
    model_type='bpe',  # 或 'unigram'
    character_coverage=0.9995,
    pad_id=0,
    unk_id=1,
    bos_id=2,
    eos_id=3
)

# 加载模型
sp = spm.SentencePieceProcessor()
sp.load('m.model')

# 编码
text = "This is a test sentence."
tokens = sp.encode_as_pieces(text)
print(f"Tokens: {tokens}")

ids = sp.encode_as_ids(text)
print(f"IDs: {ids}")

# 解码
decoded = sp.decode_pieces(tokens)
print(f"Decoded: {decoded}")
```

**使用预训练模型**:

```python
import sentencepiece as spm

# 加载预训练模型（如 BERT 的 tokenizer）
sp = spm.SentencePieceProcessor()
sp.load('bert-base-uncased.model')

# 编码
text = "Hello, world!"
tokens = sp.encode_as_pieces(text)
# ['▁Hello', ',', '▁world', '!']
```

**运行示例**:
```bash
python sentencepiece/sentencepieceTest.py
```

---

## 分词方法对比

| 方法 | 优点 | 缺点 | 适用场景 |
|------|------|------|---------|
| **空格分割** | 简单快速 | 无法处理未登录词、标点符号 | 简单文本处理 |
| **正则表达式** | 灵活可控 | 需要手动设计规则 | 特定格式文本 |
| **BPE** | 平衡词汇表大小和表达能力 | 训练时间较长 | 通用文本（GPT 系列） |
| **WordPiece** | 基于似然最大化 | 训练复杂 | 英文为主（BERT） |
| **Unigram** | 概率模型，支持多种分词 | 计算开销大 | 多语言（XLNet） |
| **SentencePiece** | 语言无关，端到端 | 需要大量训练数据 | 多语言模型 |

## 快速开始

### 安装依赖

```bash
pip install transformers sentencepiece tiktoken
```

### 运行所有测试

```bash
cd tokenization

# BPE 算法
python bpe.py

# GPT-2 Encoder
python encoderGpt2.py

# 正则表达式测试
python regexTest.py

# DeepSeek R1 Tokenizer
python deepseekr1/tokenizerTest.py

# SentencePiece
python sentencepiece/sentencepieceTest.py
```

## 核心概念

### 1. 子词分词 (Subword Tokenization)

**目的**: 在字符级和词级之间找到平衡

**优势**:
- 词汇表大小可控
- 处理未登录词（OOV）
- 支持形态丰富的语言

**常见方法**:
- BPE: 基于频率的贪心合并
- WordPiece: 基于似然的合并
- Unigram: 基于概率的分词

### 2. BPE 训练过程

```
初始状态: 所有字节 (0-255)
迭代 1: 合并 (101, 115) → 256  # "es"
迭代 2: 合并 (116, 104) → 257  # "th"
迭代 3: 合并 (105, 110) → 258  # "in"
...
迭代 N: 达到目标词汇表大小
```

### 3. 特殊 Token

| Token | 用途 | 示例 |
|-------|------|------|
| `<pad>` | 填充 | 批处理时对齐序列长度 |
| `<unk>` | 未知词 | 词汇表外的 token |
| `<bos>` | 序列开始 | 标记文本开始 |
| `<eos>` | 序列结束 | 标记文本结束 |
| `<sep>` | 分隔符 | 分隔多个句子 |
| `<cls>` | 分类 | BERT 的分类 token |
| `<mask>` | 掩码 | BERT 的掩码语言模型 |

### 4. 编码流程

```
原始文本
    ↓
预处理（小写化、Unicode 规范化）
    ↓
正则分割（处理缩写、标点）
    ↓
BPE 编码（应用合并规则）
    ↓
转换为 ID（查词汇表）
    ↓
添加特殊 Token（<bos>, <eos>）
    ↓
最终 Token IDs
```

## 实验建议

### 实验 1: 词汇表大小的影响

```python
# 小词汇表（1000）
vocab_sizes = [1000, 5000, 10000, 30000, 50000]

for vocab_size in vocab_sizes:
    # 训练 BPE
    # 测试压缩率、OOV 率
```

**观察指标**:
- 压缩率（原始字符数 / token 数）
- OOV 率（未登录词比例）
- 训练时间

### 实验 2: 不同语言的分词效果

```python
texts = {
    'en': "Hello, world!",
    'zh': "你好，世界！",
    'ja': "こんにちは、世界！",
    'ar': "مرحبا بالعالم!"
}

for lang, text in texts.items():
    tokens = tokenizer.encode(text)
    print(f"{lang}: {len(tokens)} tokens")
```

### 实验 3: 正则模式的影响

```python
patterns = [
    r'\s+',                                    # 简单空格
    r'([,.:;?_!"()\']|--|\s)',                # 保留标点
    r"'s|'t|'re|'ve|'m|'ll|'d| ?\w+| ?[^\s\w]+|\s+",  # GPT-2
]

for pattern in patterns:
    tokens = re.findall(pattern, text)
    print(f"Pattern: {pattern}")
    print(f"Tokens: {tokens}\n")
```

## 常见问题

### Q: BPE 和 WordPiece 有什么区别？

A: 
- **BPE**: 基于频率，贪心合并最高频的 pair
- **WordPiece**: 基于似然，选择能最大化训练数据似然的合并

### Q: 为什么 GPT-2 使用字节级 BPE？

A: 字节级 BPE 的优势：
1. 词汇表大小固定（256 个基础字节）
2. 可以表示任何 Unicode 字符
3. 无需预处理和清洗

### Q: SentencePiece 和 BPE 有什么区别？

A: SentencePiece 是一个框架，可以实现多种算法（包括 BPE）：
- 语言无关（不依赖空格分词）
- 端到端训练（直接从原始文本）
- 支持多种算法（BPE、Unigram）

### Q: 如何选择词汇表大小？

A: 考虑因素：
- **任务类型**: 生成任务需要更大的词汇表
- **语言特性**: 形态丰富的语言需要更大的词汇表
- **计算资源**: 更大的词汇表需要更多内存
- **经验值**: GPT-2 (50k), BERT (30k), T5 (32k)

## 相关资源

- [BPE 原始论文](https://arxiv.org/abs/1508.07909)
- [SentencePiece 论文](https://arxiv.org/abs/1808.06226)
- [Hugging Face Tokenizers 文档](https://huggingface.co/docs/tokenizers/)
- [OpenAI tiktoken](https://github.com/openai/tiktoken)

## 许可证

本项目仅供学习和演示使用。
