"""
医疗文档爬虫配置文件
"""

# 爬虫基本配置
CRAWLER_CONFIG = {
    # 输出目录
    'output_dir': 'medical_docs',
    
    # 目标文档数量
    'target_count': 50,
    
    # 请求超时时间（秒）
    'request_timeout': 10,
    
    # 请求间隔（秒）
    'request_delay': 1,
    
    # 列表页请求间隔（秒）
    'list_page_delay': 2,
    
    # 内容最小长度（字符数）
    'min_content_length': 100,
    
    # 每个来源的最大文档数
    'max_docs_per_source': 10,
    
    # User-Agent
    'user_agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36',
}


# 数据源配置
DATA_SOURCES = {
    # 国家医保局
    'nhsa': {
        'name': '国家医保局',
        'base_url': 'http://www.nhsa.gov.cn',
        'enabled': True,
        'list_urls': [
            '/art/2021/1/29/art_14_4237.html',  # 政策法规
            '/col/col6522/index.html',           # 工作动态
        ],
        'max_docs': 20,
    },
    
    # 国家卫健委
    'nhc': {
        'name': '国家卫健委',
        'base_url': 'http://www.nhc.gov.cn',
        'enabled': True,
        'list_urls': [
            '/wjw/zcjd/list.shtml',   # 政策解读
            '/wjw/gfxwj/list.shtml',  # 规范性文件
        ],
        'max_docs': 20,
    },
    
    # 医疗指南
    'guides': {
        'name': '医疗指南',
        'enabled': True,
        'max_docs': 10,
    },
}


# 内容提取器配置
CONTENT_EXTRACTORS = [
    # 优先使用的选择器
    {'class': 'TRS_Editor'},
    {'id': 'content'},
    {'class': 'content'},
    {'class': 'article-content'},
    {'id': 'article'},
    {'class': 'main-content'},
    {'class': 'article'},
    {'class': 'detail-content'},
    {'id': 'articleContent'},
]


# 关键词过滤配置（可选）
KEYWORD_FILTERS = {
    # 必须包含的关键词（至少一个）
    'include': [
        '医保', '医疗', '诊疗', '治疗', '疾病',
        '药品', '医院', '患者', '临床', '指南'
    ],
    
    # 必须排除的关键词
    'exclude': [
        '招聘', '采购', '公告'
    ],
    
    # 是否启用关键词过滤
    'enabled': False,
}


# 日志配置
LOGGING_CONFIG = {
    'level': 'INFO',  # DEBUG, INFO, WARNING, ERROR
    'format': '%(asctime)s - %(levelname)s - %(message)s',
    'file': None,  # 设置为文件路径以保存日志到文件
}


# 保存格式配置
SAVE_CONFIG = {
    'save_json': True,
    'save_text': True,
    'save_markdown': False,
    
    # 文件名模板
    'json_filename': 'medical_documents_{timestamp}.json',
    'text_filename': 'medical_documents_{timestamp}.txt',
    'markdown_filename': 'medical_documents_{timestamp}.md',
}


# 高级配置
ADVANCED_CONFIG = {
    # 是否使用代理
    'use_proxy': False,
    'proxy': {
        'http': 'http://proxy.example.com:8080',
        'https': 'http://proxy.example.com:8080',
    },
    
    # 重试配置
    'retry_times': 3,
    'retry_delay': 2,
    
    # 是否保存失败的URL
    'save_failed_urls': True,
    
    # 是否验证SSL证书
    'verify_ssl': True,
    
    # 并发请求数（0表示不使用并发）
    'concurrent_requests': 0,
}


# 质量控制配置
QUALITY_CONFIG = {
    # 最小标题长度
    'min_title_length': 10,
    
    # 最大标题长度
    'max_title_length': 200,
    
    # 最小内容长度
    'min_content_length': 100,
    
    # 最大内容长度（0表示不限制）
    'max_content_length': 0,
    
    # 是否去除HTML标签
    'remove_html_tags': True,
    
    # 是否标准化空白字符
    'normalize_whitespace': True,
}

