"""
医疗数据集爬取工具
从医保局、卫健委、医院等官方网站抓取高质量文档
"""

import requests
from bs4 import BeautifulSoup
import json
import time
import os
from datetime import datetime
from typing import List, Dict
import re
from urllib.parse import urljoin, urlparse
import logging

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class MedicalDocumentCrawler:
    """医疗文档爬虫"""
    
    def __init__(self, output_dir="medical_docs"):
        """
        初始化爬虫
        
        Args:
            output_dir: 输出目录
        """
        self.output_dir = output_dir
        self.headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
        }
        self.session = requests.Session()
        self.session.headers.update(self.headers)
        self.documents = []
        
        # 创建输出目录
        os.makedirs(output_dir, exist_ok=True)
        
    def crawl_nhsa_gov(self) -> List[Dict]:
        """
        爬取国家医保局网站
        http://www.nhsa.gov.cn/
        """
        logger.info("开始爬取国家医保局网站...")
        docs = []
        
        try:
            # 医保政策文件列表页
            base_url = "http://www.nhsa.gov.cn"
            list_urls = [
                f"{base_url}/col/col104/index.html",  # 政策法规
                f"{base_url}/col/col14/index.html",   # 医保动态
            ]
            
            for list_url in list_urls:
                try:
                    response = self.session.get(list_url, timeout=10)
                    response.encoding = 'utf-8'
                    #用html.parser解析，避免使用lxml-xml解析失败
                    soup = BeautifulSoup(response.text, 'html.parser')
                    
                    # 优先从datastore脚本中提取真实列表（页面使用text/xml + dataproxy）
                    candidate_links = self._extract_nhsa_links_from_datastore(soup, base_url)
                    count = 0
                    filtered_count = 0
                    
                    for link in candidate_links:
                        if count >= 10:  # 每个栏目抓取10篇
                            break
                            
                        href = link.get('href', '') if isinstance(link, dict) else link.get('href', '')
                        title = link.get('title', '') if isinstance(link, dict) else (link.get_text(strip=True) or link.get('title', ''))
                        
                        # 过滤有效的文章链接
                        if href and 'art' in href and len(title) > 10:
                            filtered_count += 1
                            full_url = urljoin(base_url, href)
                            
                            # 获取文章内容
                            doc_content = self._fetch_article_content(full_url)
                            if doc_content:
                                docs.append({
                                    'title': title,
                                    'url': full_url,
                                    'source': '国家医保局',
                                    'content': doc_content,
                                    'crawl_time': datetime.now().isoformat()
                                })
                                count += 1
                                time.sleep(1)  # 避免请求过快
                            else:
                                logger.warning(f"✗ 内容提取失败: {title[:50]}...")
                    
                except Exception as e:
                    logger.error(f"抓取医保局列表页失败 {list_url}: {str(e)}")
                    import traceback
                    logger.debug(traceback.format_exc())
                    continue
                    
        except Exception as e:
            logger.error(f"抓取医保局网站失败: {str(e)}")
            
        return docs
    
    def _extract_nhsa_links_from_datastore(self, soup: BeautifulSoup, base_url: str) -> List[Dict]:
        """
        从医保局页面中的datastore脚本提取文章链接（该站点列表通过text/xml维护）
        """
        results: List[Dict] = []
        try:
            scripts = soup.find_all('script', type='text/xml')
            for script in scripts:
                xml_text = script.string or script.get_text()
                if not xml_text or '<datastore' not in xml_text:
                    continue
                
                # 解析datastore中的record片段（容错：若xml解析失败则回退lxml-xml/html）
                xml_soup = BeautifulSoup(xml_text, 'html.parser')
                records = xml_soup.find_all('record')
                for rec in records:
                    cdata_html = rec.string or rec.get_text()
                    if not cdata_html:
                        continue
                    frag = BeautifulSoup(cdata_html, 'html.parser')
                    a_tag = frag.find('a', href=True)
                    if not a_tag:
                        continue
                    href = a_tag.get('href', '')
                    title = a_tag.get_text(strip=True) or a_tag.get('title', '')
                    if href and title:
                        results.append({
                            'href': href,
                            'title': title,
                            'url': urljoin(base_url, href)
                        })
        except Exception as e:
            logger.warning(f"解析医保局datastore失败: {str(e)}")
        
        return results
    
    def _build_page_url(self, list_url: str, page: int) -> str:
        if page <= 1:
            return list_url
        return list_url.replace("list.shtml", f"list_{page}.shtml")

    def crawl_nhc_gov(self) -> List[Dict]:
        """
        爬取国家卫健委网站
        http://www.nhc.gov.cn/
        """
        logger.info("开始爬取国家卫健委网站...")
        docs = []
        
        try:
            base_url = "http://www.nhc.gov.cn"
            list_urls = [
                f"{base_url}/wjw/zcjd/list.shtml",  # 政策解读
                f"{base_url}/wjw/gfxwj/list.shtml",  # 规范性文件
            ]
            
            for list_url in list_urls:
                # 每个栏目抓取5页
                for page in range(1, 5):
                    page_url = self._build_page_url(list_url, page)
                    logger.info(f"抓取列表页: {page_url}")

                    try:
                        response = self.session.get(page_url, timeout=10)
                        response.encoding = 'utf-8'
                        soup = BeautifulSoup(response.text, 'html.parser')
                        
                        links = soup.find_all('a', href=True)
                        count = 0
                        
                        for link in links:
                            # 每个页面抓取10篇
                            if count >= 10:
                                break
                                
                            href = link.get('href', '')
                            # 优先使用链接文本，如果为空则使用title属性
                            title = link.get_text(strip=True) or link.get('title', '')
                            
                            if href and '.shtml' in href and len(title) > 10:
                                full_url = urljoin(base_url, href)
                                
                                doc_content = self._fetch_article_content(full_url)
                                if doc_content:
                                    docs.append({
                                        'title': title,
                                        'url': full_url,
                                        'source': '国家卫健委',
                                        'content': doc_content,
                                        'crawl_time': datetime.now().isoformat()
                                    })
                                    count += 1
                                    time.sleep(1)
                        if count == 0:
                           logger.info(f"本页无新增文章，停止翻页: {page_url}")
                           break
                    except Exception as e:
                        logger.error(f"抓取卫健委列表页失败 {list_url}: {str(e)}")
                        continue
                    
        except Exception as e:
            logger.error(f"抓取卫健委网站失败: {str(e)}")
            
        return docs
    
    def crawl_hospital_guides(self) -> List[Dict]:
        """
        爬取医院公开指南（示例：使用备选公开医疗知识库）
        """
        logger.info("开始爬取医疗指南...")
        docs = []
        
        # 这里可以添加具体医院网站的爬取逻辑
        # 或者使用公开的医疗知识数据源
        
        try:
            # 示例：爬取医学百科等公开资源
            # 实际使用时需要替换为真实的医院指南网站
            sample_guides = [
                {
                    'title': '高血压诊疗指南',
                    'url': 'https://example.com/hypertension-guide',
                    'source': '医疗指南',
                    'content': '高血压诊疗规范内容...',
                    'crawl_time': datetime.now().isoformat()
                },
                # 更多指南...
            ]
            docs.extend(sample_guides)
            
        except Exception as e:
            logger.error(f"抓取医疗指南失败: {str(e)}")
            
        return docs
    
    def _fetch_article_content(self, url: str) -> str:
        """
        获取文章正文内容
        
        Args:
            url: 文章URL
            
        Returns:
            文章内容
        """
        try:
            logger.debug(f"  正在获取内容: {url}")
            response = self.session.get(url, timeout=10)
            response.encoding = 'utf-8'
            soup = BeautifulSoup(response.text, 'html.parser')
            
            # 尝试多种常见的内容选择器
            content_selectors = [
                {'class': 'TRS_Editor'},
                {'id': 'content'},
                {'class': 'content'},
                {'class': 'article-content'},
                {'id': 'article'},
                {'class': 'main-content'},
            ]
            
            content = None
            used_selector = None
            for selector in content_selectors:
                content_div = soup.find('div', selector)
                if content_div:
                    content = content_div.get_text(strip=True, separator='\n')
                    used_selector = selector
                    break
            
            # 如果没找到，尝试获取所有段落
            if not content:
                paragraphs = soup.find_all('p')
                if paragraphs:
                    content = '\n'.join([p.get_text(strip=True) for p in paragraphs if p.get_text(strip=True)])
            
            # 清理内容
            if content:
                content = re.sub(r'\s+', ' ', content)
                content = content.strip()
                
                # 内容质量检查：至少100字
                if len(content) > 100:
                    return content
                else:
                    logger.warning(f"  内容太短: {len(content)} 字符 (需要>100)")
            else:
                logger.warning(f"  未找到任何内容")
        except Exception as e:
            logger.error(f"获取文章内容失败: {str(e)}")
            
        return None
    
    def crawl_all(self, target_count: int = 50) -> List[Dict]:
        """
        爬取所有来源的文档
        
        Args:
            target_count: 目标文档数量
            
        Returns:
            文档列表
        """
        logger.info(f"开始爬取医疗文档，目标数量: {target_count}")
        
        all_docs = []
        
        # 从各个来源爬取
        sources = [
            # ('医保局', self.crawl_nhsa_gov),
            ('卫健委', self.crawl_nhc_gov),
            # ('医疗指南', self.crawl_hospital_guides),
        ]
        
        for source_name, crawl_func in sources:
            if len(all_docs) >= target_count:
                break
                
            logger.info(f"正在爬取 {source_name}...")
            try:
                docs = crawl_func()
                all_docs.extend(docs)
                logger.info(f"{source_name} 爬取完成，获得 {len(docs)} 篇文档")
            except Exception as e:
                logger.error(f"{source_name} 爬取失败: {str(e)}")
                
            time.sleep(2)  # 休息2秒
        
        # 截取目标数量
        all_docs = all_docs[:target_count]
        self.documents = all_docs
        
        logger.info(f"爬取完成，共获得 {len(all_docs)} 篇文档")
        return all_docs
    
    def save_documents(self, filename: str = None) -> str:
        """
        保存文档到JSON文件
        
        Args:
            filename: 文件名
            
        Returns:
            保存的文件路径
        """
        if not filename:
            filename = f"medical_documents_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
            
        filepath = os.path.join(self.output_dir, filename)
        
        with open(filepath, 'w', encoding='utf-8') as f:
            json.dump(self.documents, f, ensure_ascii=False, indent=2)
            
        logger.info(f"文档已保存到: {filepath}")
        return filepath
    
    def save_documents_text(self, filename: str = None) -> str:
        """
        保存文档到文本文件（便于阅读）
        
        Args:
            filename: 文件名
            
        Returns:
            保存的文件路径
        """
        if not filename:
            filename = f"medical_documents_{datetime.now().strftime('%Y%m%d_%H%M%S')}.txt"
            
        filepath = os.path.join(self.output_dir, filename)
        
        with open(filepath, 'w', encoding='utf-8') as f:
            for i, doc in enumerate(self.documents, 1):
                f.write(f"\n{'='*80}\n")
                f.write(f"文档 {i}: {doc['title']}\n")
                f.write(f"来源: {doc['source']}\n")
                f.write(f"链接: {doc['url']}\n")
                f.write(f"抓取时间: {doc['crawl_time']}\n")
                f.write(f"{'='*80}\n\n")
                f.write(doc['content'])
                f.write("\n\n")
                
        logger.info(f"文档已保存到: {filepath}")
        return filepath
    
    def get_statistics(self) -> Dict:
        """
        获取统计信息
        
        Returns:
            统计信息字典
        """
        if not self.documents:
            return {}
            
        sources = {}
        total_length = 0
        
        for doc in self.documents:
            source = doc['source']
            sources[source] = sources.get(source, 0) + 1
            total_length += len(doc['content'])
            
        return {
            'total_documents': len(self.documents),
            'sources': sources,
            'total_content_length': total_length,
            'average_content_length': total_length // len(self.documents) if self.documents else 0
        }
    
    def print_statistics(self):
        """打印统计信息"""
        stats = self.get_statistics()
        
        if not stats:
            logger.info("暂无文档数据")
            return
            
        logger.info("\n" + "="*60)
        logger.info("医疗文档统计信息")
        logger.info("="*60)
        logger.info(f"总文档数: {stats['total_documents']}")
        logger.info(f"总内容长度: {stats['total_content_length']:,} 字符")
        logger.info(f"平均内容长度: {stats['average_content_length']:,} 字符")
        logger.info("\n各来源文档数:")
        for source, count in stats['sources'].items():
            logger.info(f"  {source}: {count} 篇")
        logger.info("="*60 + "\n")


def main():
    """主函数"""
    # 创建爬虫实例
    crawler = MedicalDocumentCrawler(output_dir="medical_docs")
    
    # 爬取30-50篇文档
    target_count = 100
    documents = crawler.crawl_all(target_count=target_count)
    
    # 保存文档
    if documents:
        json_file = crawler.save_documents()
        text_file = crawler.save_documents_text()
        
        # 打印统计信息
        crawler.print_statistics()
        
        logger.info(f"\n爬取完成！")
        logger.info(f"JSON文件: {json_file}")
        logger.info(f"文本文件: {text_file}")
    else:
        logger.warning("未能获取任何文档")


if __name__ == "__main__":
    main()

