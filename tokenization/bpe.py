# result = [ord(x) for x in "1234567890啊就是都"]
# print(result)

# result1 =  list("1234567890啊就 是 都".encode("utf-8"))
# print(result1)

text = """Species Differences:
Grévy’s zebra: Largest species, found in Kenya and Ethiopia, with narrow stripes and large ears.
Plains zebra: Most common, found across eastern and southern Africa, with varying stripe patterns.
Mountain zebra: Native to southwestern Africa, has a dewlap and grid-like stripes on the rump."""
# print("length of text: ", len(text))
tokens = text.encode("utf-8")
tokens = list(map(int, tokens))
# print(tokens)
# print("length of tokens: ", len(tokens))

def get_stats(tokens):
    stats = {}
    for pair in zip(tokens, tokens[1:]):
        stats[pair] = stats.get(pair, 0) + 1
    return stats

stats = get_stats(tokens)
# print(stats)
# print(sorted(((v,k) for k,v in stats.items()),reverse=True)[:10])
# top_pair = max(stats, key=stats.get)
# print(top_pair)

def merge_tokens(pair, tokens,idx):
    new_tokens = []
    i = 0
    while i < len(tokens):
        #如果当前元素跟pair匹配，就用新的token代替当前token，例如用256代替101,115
        if i <len(tokens) - 1 and tokens[i] == pair[0] and tokens[i+1] == pair[1]:
            new_tokens.append(idx)
            i += 2
        else:
            new_tokens.append(tokens[i])
            i += 1
    return new_tokens

# tokens = merge_tokens(top_pair, tokens, 256)
# print(tokens)


vacab_size = 300
num_merges = vacab_size-256
# print("num_merges: ", num_merges)
merged_tokens = {}
ids = tokens
for i in range(num_merges):
    stats = get_stats(ids)
    top_pair = max(stats, key=stats.get)
    idx = 256+i
    ids = merge_tokens(top_pair, ids, idx)
    merged_tokens[top_pair] = idx
print(ids)
print(merged_tokens)
# print(f"compression ratio: {len(tokens)/len(ids):.2f}X")


def decode_tokens(ids, merged_tokens):
    """
    将BPE编码后的ids解码回原始tokens
    
    参数:
        ids: 编码后的token id列表
        merged_tokens: 合并token的字典，格式为{(token1, token2): merged_id}
    
    返回:
        解码后的原始tokens列表
    """
    # 创建反向映射字典，使用字典推导式
    reverse_merges = {idx: pair for pair, idx in merged_tokens.items()}
    
    # 从高到低的顺序获取合并的token id
    merged_ids = sorted(reverse_merges.keys(), reverse=True)
    
    # 复制一份ids，避免修改原始数据
    result = ids.copy()
    
    # 逐个处理合并的token
    for idx in merged_ids:
        # 临时存储展开后的结果
        temp_result = []
        for token in result:
            if token == idx:
                # 如果是合并的token，则展开为原始pair
                pair = reverse_merges[idx]
                temp_result.extend(pair)
            else:
                # 否则保持不变
                temp_result.append(token)
        result = temp_result
    
    return result

# 测试解码功能
decoded_tokens = decode_tokens(ids, merged_tokens)
print("解码后的tokens:")
print(decoded_tokens)

# 验证解码是否正确
print(f"原始tokens长度: {len(tokens)}, 解码后tokens长度: {len(decoded_tokens)}")
if tokens == decoded_tokens:
    print("解码成功: 解码后的tokens与原始tokens完全一致")
else:
    print("解码失败: 解码后的tokens与原始tokens不一致")

# 将解码后的tokens转回原始文本
decoded_text = bytes(decoded_tokens).decode("utf-8")
print("解码后的文本:" ,decoded_text)


