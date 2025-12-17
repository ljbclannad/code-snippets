"""
医疗文档爬虫测试脚本
快速测试爬虫的各个功能
"""

import sys
from MedicalCrawler import MedicalDocumentCrawler
import logging

# 设置日志级别
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


def test_crawler_initialization():
    """测试爬虫初始化"""
    print("\n" + "="*60)
    print("测试 1: 爬虫初始化")
    print("="*60)
    
    try:
        crawler = MedicalDocumentCrawler(output_dir="test_output")
        print("✓ 爬虫初始化成功")
        print(f"  输出目录: {crawler.output_dir}")
        return True
    except Exception as e:
        print(f"✗ 爬虫初始化失败: {str(e)}")
        return False


def test_single_source():
    """测试单个数据源"""
    print("\n" + "="*60)
    print("测试 2: 单个数据源爬取（医保局）")
    print("="*60)
    
    try:
        crawler = MedicalDocumentCrawler()
        
        # 只爬取医保局的前3篇
        logger.info("尝试从医保局爬取文档...")
        docs = crawler.crawl_nhsa_gov()
        
        if docs and len(docs) > 0:
            print(f"✓ 成功从医保局获取 {len(docs)} 篇文档")
            print(f"\n示例文档:")
            for i, doc in enumerate(docs[:3], 1):
                print(f"  {i}. {doc['title'][:50]}...")
                print(f"     URL: {doc['url']}")
                print(f"     内容长度: {len(doc['content'])} 字符")
            return True
        else:
            print("⚠ 未能获取文档，可能是网络问题或网站结构变化")
            return False
            
    except Exception as e:
        print(f"✗ 测试失败: {str(e)}")
        return False


def test_content_extraction():
    """测试内容提取"""
    print("\n" + "="*60)
    print("测试 3: 内容提取功能")
    print("="*60)
    
    try:
        crawler = MedicalDocumentCrawler()
        
        # 测试URL
        test_url = "http://www.nhsa.gov.cn/art/2021/1/29/art_14_4237.html"
        
        logger.info(f"尝试提取内容: {test_url}")
        content = crawler._fetch_article_content(test_url)
        
        if content and len(content) > 0:
            print(f"✓ 内容提取成功")
            print(f"  内容长度: {len(content)} 字符")
            print(f"  内容预览: {content[:100]}...")
            return True
        else:
            print("⚠ 内容提取失败或内容为空")
            return False
            
    except Exception as e:
        print(f"✗ 测试失败: {str(e)}")
        return False


def test_save_functionality():
    """测试保存功能"""
    print("\n" + "="*60)
    print("测试 4: 文档保存功能")
    print("="*60)
    
    try:
        crawler = MedicalDocumentCrawler(output_dir="test_output")
        
        # 创建测试文档
        test_docs = [
            {
                'title': '测试文档1',
                'url': 'http://example.com/doc1',
                'source': '测试来源',
                'content': '这是测试内容' * 50,
                'crawl_time': '2025-12-17T00:00:00'
            }
        ]
        
        crawler.documents = test_docs
        
        # 测试JSON保存
        json_file = crawler.save_documents(filename="test_documents.json")
        print(f"✓ JSON保存成功: {json_file}")
        
        # 测试文本保存
        text_file = crawler.save_documents_text(filename="test_documents.txt")
        print(f"✓ 文本保存成功: {text_file}")
        
        return True
        
    except Exception as e:
        print(f"✗ 测试失败: {str(e)}")
        return False


def test_statistics():
    """测试统计功能"""
    print("\n" + "="*60)
    print("测试 5: 统计功能")
    print("="*60)
    
    try:
        crawler = MedicalDocumentCrawler()
        
        # 创建测试文档
        test_docs = [
            {
                'title': '测试文档1',
                'url': 'http://example.com/doc1',
                'source': '医保局',
                'content': '内容' * 100,
                'crawl_time': '2025-12-17'
            },
            {
                'title': '测试文档2',
                'url': 'http://example.com/doc2',
                'source': '卫健委',
                'content': '内容' * 200,
                'crawl_time': '2025-12-17'
            }
        ]
        
        crawler.documents = test_docs
        stats = crawler.get_statistics()
        
        print(f"✓ 统计功能正常")
        print(f"  总文档数: {stats['total_documents']}")
        print(f"  来源分布: {stats['sources']}")
        print(f"  平均长度: {stats['average_content_length']} 字符")
        
        return True
        
    except Exception as e:
        print(f"✗ 测试失败: {str(e)}")
        return False


def test_full_crawl_small():
    """测试完整爬取流程（小规模）"""
    print("\n" + "="*60)
    print("测试 6: 完整爬取流程（5篇文档）")
    print("="*60)
    
    try:
        crawler = MedicalDocumentCrawler(output_dir="test_output")
        
        logger.info("开始小规模爬取测试...")
        documents = crawler.crawl_all(target_count=5)
        
        if documents and len(documents) > 0:
            print(f"✓ 爬取成功，获得 {len(documents)} 篇文档")
            
            # 保存文档
            json_file = crawler.save_documents(filename="test_crawl.json")
            
            # 显示统计
            crawler.print_statistics()
            
            return True
        else:
            print("⚠ 未能获取文档")
            return False
            
    except Exception as e:
        print(f"✗ 测试失败: {str(e)}")
        return False


def run_all_tests():
    """运行所有测试"""
    print("\n" + "="*60)
    print("医疗文档爬虫功能测试")
    print("="*60)
    
    tests = [
        ("爬虫初始化", test_crawler_initialization),
        ("单数据源爬取", test_single_source),
        ("内容提取", test_content_extraction),
        ("保存功能", test_save_functionality),
        ("统计功能", test_statistics),
        ("完整爬取", test_full_crawl_small),
    ]
    
    results = []
    
    for test_name, test_func in tests:
        try:
            result = test_func()
            results.append((test_name, result))
        except Exception as e:
            logger.error(f"测试 {test_name} 出现异常: {str(e)}")
            results.append((test_name, False))
    
    # 输出测试报告
    print("\n" + "="*60)
    print("测试报告")
    print("="*60)
    
    passed = sum(1 for _, result in results if result)
    total = len(results)
    
    for test_name, result in results:
        status = "✓ 通过" if result else "✗ 失败"
        print(f"{status} - {test_name}")
    
    print(f"\n总计: {passed}/{total} 测试通过")
    print("="*60)
    
    return passed == total


if __name__ == "__main__":
    print("\n医疗文档爬虫测试工具")
    print("注意：某些测试需要网络连接\n")
    
    # 可以选择运行单个测试或所有测试
    if len(sys.argv) > 1:
        test_num = sys.argv[1]
        tests = {
            '1': test_crawler_initialization,
            '2': test_single_source,
            '3': test_content_extraction,
            '4': test_save_functionality,
            '5': test_statistics,
            '6': test_full_crawl_small,
        }
        
        if test_num in tests:
            tests[test_num]()
        else:
            print(f"未知的测试编号: {test_num}")
            print("可用的测试: 1-6, 或不带参数运行所有测试")
    else:
        # 运行所有测试
        success = run_all_tests()
        sys.exit(0 if success else 1)

