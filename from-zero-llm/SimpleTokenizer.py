import re
class SimpleTokenizerV1:
    def __init__(self, vocab):
       self.str_to_int = vocab
       self.int_to_str = {v:k for k,v in vocab.items()}

    
    def encode(self, text):
        preprocessed = re.split(r'([,.:;?_!"()\']|--|\s)', text)
        preprocessed = [item.strip() for item in preprocessed if item.strip()]
        integers = [self.str_to_int[word] for word in preprocessed]
        return integers

    # 将token列表转换为文本
    def decode(self, tokens):
       text = " ".join([self.int_to_str[token] for token in tokens])
       text = re.sub(r'([,.:;?_!"()\'])', r'\1', text)
       return text


class SimpleTokenizerV2:
    def __init__(self, vocab):
       self.str_to_int = vocab
       self.int_to_str = {v:k for k,v in vocab.items()}

    def encode(self, text):
        preprocessed = re.split(r'([,.:;?_!"()\']|--|\s)', text)
        preprocessed = [item.strip() for item in preprocessed if item.strip()]
        preprocessed = [item if item in self.str_to_int 
        else "<|unk|>" for item in preprocessed] # 如果单词不在词汇表中，则替换为未知符
        integers = [self.str_to_int[word] for word in preprocessed]
        return integers

    def decode(self, tokens):
        text = " ".join([self.int_to_str[token] for token in tokens])
        text = re.sub(r'([,.:;?_!"()\'])', r'\1', text)
        return text