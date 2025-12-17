"""
医疗文档爬虫使用示例
"""

from MedicalCrawler import MedicalDocumentCrawler
import json


def example_basic_usage():
    """基本使用示例"""
    print("=" * 60)
    print("示例 1: 基本使用")
    print("=" * 60)
    
    # 创建爬虫实例
    crawler = MedicalDocumentCrawler(output_dir="medical_docs")
    
    # 爬取30篇文档
    documents = crawler.crawl_all(target_count=30)
    
    # 保存文档
    if documents:
        json_file = crawler.save_documents()
        text_file = crawler.save_documents_text()
        
        # 打印统计信息
        crawler.print_statistics()
        
        print(f"\n爬取完成！")
        print(f"JSON文件: {json_file}")
        print(f"文本文件: {text_file}")


def example_single_source():
    """单个数据源爬取示例"""
    print("\n" + "=" * 60)
    print("示例 2: 只爬取医保局数据")
    print("=" * 60)
    
    crawler = MedicalDocumentCrawler()
    
    # 只爬取医保局
    nhsa_docs = crawler.crawl_nhsa_gov()
    
    print(f"从医保局获取了 {len(nhsa_docs)} 篇文档")
    
    # 显示前3篇文档的标题
    for i, doc in enumerate(nhsa_docs[:3], 1):
        print(f"{i}. {doc['title']}")
        print(f"   链接: {doc['url']}\n")


def example_custom_processing():
    """自定义处理示例"""
    print("\n" + "=" * 60)
    print("示例 3: 自定义文档处理")
    print("=" * 60)
    
    crawler = MedicalDocumentCrawler()
    documents = crawler.crawl_all(target_count=20)
    
    # 按来源分组
    by_source = {}
    for doc in documents:
        source = doc['source']
        if source not in by_source:
            by_source[source] = []
        by_source[source].append(doc)
    
    print("\n文档来源分布:")
    for source, docs in by_source.items():
        print(f"  {source}: {len(docs)} 篇")
    
    # 找出最长的文档
    if documents:
        longest_doc = max(documents, key=lambda x: len(x['content']))
        print(f"\n最长的文档:")
        print(f"  标题: {longest_doc['title']}")
        print(f"  长度: {len(longest_doc['content'])} 字符")
        print(f"  链接: {longest_doc['url']}")


def example_filter_documents():
    """文档过滤示例"""
    print("\n" + "=" * 60)
    print("示例 4: 过滤特定主题的文档")
    print("=" * 60)
    
    crawler = MedicalDocumentCrawler()
    documents = crawler.crawl_all(target_count=30)
    
    # 过滤包含"医保"关键词的文档
    keyword = "医保"
    filtered_docs = [
        doc for doc in documents 
        if keyword in doc['title'] or keyword in doc['content']
    ]
    
    print(f"\n包含'{keyword}'关键词的文档: {len(filtered_docs)} 篇")
    
    # 显示前5篇
    for i, doc in enumerate(filtered_docs[:5], 1):
        print(f"{i}. {doc['title']}")


def example_export_urls_only():
    """导出URL列表示例"""
    print("\n" + "=" * 60)
    print("示例 5: 导出所有文档URL")
    print("=" * 60)
    
    crawler = MedicalDocumentCrawler()
    documents = crawler.crawl_all(target_count=30)
    
    # 导出URL列表
    urls_file = "medical_docs/document_urls.txt"
    with open(urls_file, 'w', encoding='utf-8') as f:
        for i, doc in enumerate(documents, 1):
            f.write(f"{i}. {doc['title']}\n")
            f.write(f"   {doc['url']}\n")
            f.write(f"   来源: {doc['source']}\n\n")
    
    print(f"\nURL列表已导出到: {urls_file}")


def example_statistics_analysis():
    """统计分析示例"""
    print("\n" + "=" * 60)
    print("示例 6: 详细统计分析")
    print("=" * 60)
    
    crawler = MedicalDocumentCrawler()
    documents = crawler.crawl_all(target_count=30)
    
    # 获取基本统计
    stats = crawler.get_statistics()
    
    print("\n基本统计:")
    print(f"  总文档数: {stats['total_documents']}")
    print(f"  总字符数: {stats['total_content_length']:,}")
    print(f"  平均长度: {stats['average_content_length']:,}")
    
    print("\n各来源统计:")
    for source, count in stats['sources'].items():
        percentage = (count / stats['total_documents']) * 100
        print(f"  {source}: {count} 篇 ({percentage:.1f}%)")
    
    # 内容长度分布
    lengths = [len(doc['content']) for doc in documents]
    if lengths:
        print("\n内容长度分布:")
        print(f"  最短: {min(lengths):,} 字符")
        print(f"  最长: {max(lengths):,} 字符")
        print(f"  中位数: {sorted(lengths)[len(lengths)//2]:,} 字符")


if __name__ == "__main__":
    print("\n医疗文档爬虫使用示例\n")
    
    # 选择要运行的示例
    examples = {
        '1': ('基本使用', example_basic_usage),
        '2': ('单个数据源爬取', example_single_source),
        '3': ('自定义文档处理', example_custom_processing),
        '4': ('文档过滤', example_filter_documents),
        '5': ('导出URL列表', example_export_urls_only),
        '6': ('统计分析', example_statistics_analysis),
    }
    
    print("可用的示例:")
    for key, (name, _) in examples.items():
        print(f"  {key}. {name}")
    print("  0. 运行所有示例")
    
    choice = input("\n请选择要运行的示例 (默认: 1): ").strip() or '1'
    
    if choice == '0':
        # 运行所有示例
        for _, (_, func) in examples.items():
            func()
    elif choice in examples:
        # 运行选择的示例
        _, func = examples[choice]
        func()
    else:
        print("无效的选择！")

