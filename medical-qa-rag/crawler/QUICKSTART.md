# 快速开始指南

## 📦 安装

1. **安装依赖**

```bash
cd medical-qa-rag
pip install -r requirements.txt
```

## 🚀 快速运行

### 方法1: 直接运行主程序

最简单的方式，直接爬取50篇文档：

```bash
python MedicalDataSet.py
```

程序会自动：
- 从医保局、卫健委等网站爬取文档
- 保存为JSON和文本两种格式
- 显示统计信息

输出文件位置：`medical_docs/` 目录

### 方法2: 使用示例脚本

运行交互式示例：

```bash
python example_usage.py
```

选择你想要的功能：
1. 基本使用
2. 单个数据源爬取
3. 自定义文档处理
4. 文档过滤
5. 导出URL列表
6. 统计分析

### 方法3: 在代码中使用

```python
from MedicalDataSet import MedicalDocumentCrawler

# 创建爬虫
crawler = MedicalDocumentCrawler()

# 爬取30篇文档
docs = crawler.crawl_all(target_count=30)

# 保存
crawler.save_documents()
crawler.save_documents_text()

# 查看统计
crawler.print_statistics()
```

## 🧪 测试

运行测试确保一切正常：

```bash
python test_crawler.py
```

这会运行6个测试：
- ✓ 爬虫初始化
- ✓ 单数据源爬取
- ✓ 内容提取
- ✓ 保存功能
- ✓ 统计功能
- ✓ 完整爬取

## 📁 输出文件

运行后会在 `medical_docs/` 目录生成：

- **JSON格式**: `medical_documents_YYYYMMDD_HHMMSS.json`
  - 包含完整的结构化数据
  - 每篇文档包含：标题、URL、来源、内容、抓取时间

- **文本格式**: `medical_documents_YYYYMMDD_HHMMSS.txt`
  - 便于阅读的格式
  - 包含所有文档的完整信息

## 📊 数据格式

### JSON格式示例

```json
[
  {
    "title": "关于做好医保政策解读工作的通知",
    "url": "http://www.nhsa.gov.cn/art/2021/...",
    "source": "国家医保局",
    "content": "为了进一步做好医保政策解读...",
    "crawl_time": "2025-12-17T10:30:00"
  }
]
```

## ⚙️ 自定义配置

修改 `config.py` 来自定义爬虫行为：

```python
# 修改目标数量
CRAWLER_CONFIG['target_count'] = 30

# 调整请求延迟
CRAWLER_CONFIG['request_delay'] = 2

# 修改内容最小长度
CRAWLER_CONFIG['min_content_length'] = 200
```

## 🎯 常见任务

### 只爬取医保局数据

```python
from MedicalDataSet import MedicalDocumentCrawler

crawler = MedicalDocumentCrawler()
docs = crawler.crawl_nhsa_gov()
```

### 过滤特定主题

```python
crawler = MedicalDocumentCrawler()
all_docs = crawler.crawl_all(target_count=50)

# 过滤包含"医保报销"的文档
filtered = [d for d in all_docs if "医保报销" in d['title']]
```

### 导出URL列表

```python
crawler = MedicalDocumentCrawler()
docs = crawler.crawl_all(target_count=30)

with open('urls.txt', 'w') as f:
    for doc in docs:
        f.write(f"{doc['title']}\n{doc['url']}\n\n")
```

## ⚠️ 注意事项

1. **网络要求**: 需要稳定的网络连接
2. **运行时间**: 爬取50篇文档约需5-10分钟
3. **请求频率**: 内置延迟保护，请勿修改
4. **合法使用**: 仅用于学习和研究目的

## 🐛 故障排除

### 问题：无法获取文档

**解决方案**:
- 检查网络连接
- 查看 `医保局/卫健委` 网站是否可访问
- 增加请求延迟时间

### 问题：内容为空

**解决方案**:
- 网站结构可能已变化
- 修改 `_fetch_article_content` 中的选择器
- 查看日志了解详细错误

### 问题：爬取速度慢

**解决方案**:
- 这是正常的，为了避免对网站造成压力
- 可以减少 `target_count` 数量
- 不建议减少延迟时间

## 📖 更多资源

- 详细文档: 查看 `README.md`
- 使用示例: 运行 `example_usage.py`
- 配置说明: 查看 `config.py`
- 测试脚本: 运行 `test_crawler.py`

## 💡 提示

- 首次运行建议使用较小的 `target_count`（如10-20）进行测试
- 定期运行可以获取最新的政策文档
- 保存的JSON文件可直接用于RAG系统
- 文本文件便于人工审查文档质量

## 🎉 完成！

现在你已经可以开始爬取医疗文档了！

```bash
# 最简单的开始方式
python MedicalCrawler.py
```

祝使用愉快！ 🚀

