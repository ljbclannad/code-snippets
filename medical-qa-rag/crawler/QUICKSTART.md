# 爬虫快速开始

## 安装

```bash
pip install -r requirements.txt
```

## 最快路径

在项目根目录执行：

```bash
python rag_pipeline.py crawl --target-count 30 --output-dir medical_docs
```

会生成两类文件：

- `medical_documents_*.json`
- `medical_documents_*.txt`

## 直接运行爬虫脚本

```bash
python crawler/MedicalCrawler.py --target-count 30 --output-dir medical_docs
```

## 在代码中使用

```python
from MedicalCrawler import MedicalDocumentCrawler

crawler = MedicalDocumentCrawler(output_dir="medical_docs")
docs = crawler.crawl_all(target_count=30)
crawler.save_documents()
crawler.save_documents_text()
crawler.print_statistics()
```

## 测试

```bash
python crawler/test_crawler.py
```

## 注意

- 站点结构变化会直接影响抓取结果
- 爬虫内置了请求间隔，不建议再压低
- 采集结果建议先人工抽样，再进入后续清洗和建库流程
